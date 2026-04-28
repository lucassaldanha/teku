/*
 * Copyright Consensys Software Inc., 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.statetransition.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitlist;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.kzg.KZG;
import tech.pegasys.teku.kzg.trusted_setups.TrustedSetupLoader;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.DataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderSchemaFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarSchemaFulu;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;
import tech.pegasys.teku.spec.logic.common.statetransition.availability.AvailabilityCheckerFactory;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsFulu;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class PartialDataColumnSidecarGossipValidatorTest {

  private static final Spec SPEC =
      TestSpecFactory.createMinimalFulu(
          b ->
              b.fuluBuilder(
                  f ->
                      f.cellsPerExtBlob(128)
                          .numberOfColumns(128)
                          .numberOfCustodyGroups(128)
                          .custodyRequirement(4)
                          .validatorCustodyRequirement(8)
                          .balancePerAdditionalCustodyGroup(UInt64.valueOf(32000000000L))
                          .samplesPerSlot(16)));

  @BeforeAll
  static void initKzg() {
    final KZG kzg = KZG.getInstance(false);
    TrustedSetupLoader.loadTrustedSetupForTests(kzg);
    SPEC.reinitializeForTesting(
        AvailabilityCheckerFactory.NOOP_BLOB_SIDECAR,
        AvailabilityCheckerFactory.NOOP_DATACOLUMN_SIDECAR,
        kzg);
  }

  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(SPEC);
  private final SchemaDefinitionsFulu schemas =
      SchemaDefinitionsFulu.required(SPEC.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());
  private final GossipValidationHelper gossipValidationHelper = mock(GossipValidationHelper.class);
  private final Map<Bytes32, PartialDataColumnHeaderFulu> validatedHeaders = new HashMap<>();

  private PartialDataColumnSidecarGossipValidator validator;

  private UInt64 slot;
  private Bytes32 blockRoot;
  private PartialDataColumnHeaderFulu cachedHeader;
  private int columnIndex;

  @BeforeEach
  void setup() {
    validator =
        new PartialDataColumnSidecarGossipValidator(SPEC, gossipValidationHelper, validatedHeaders);

    slot = UInt64.valueOf(2);
    columnIndex = 0;

    final SignedBeaconBlock block =
        dataStructureUtil.randomSignedBeaconBlock(
            slot.longValue(), dataStructureUtil.randomBytes32());
    final DataColumnSidecar realSidecar =
        dataStructureUtil.randomDataColumnSidecar(block, UInt64.ZERO);
    final DataColumnSidecarFulu realFulu = DataColumnSidecarFulu.required(realSidecar);

    final PartialDataColumnHeaderSchemaFulu headerSchema =
        schemas.getPartialDataColumnHeaderSchema();
    cachedHeader =
        headerSchema.create(
            realFulu.getKzgCommitments(),
            realFulu.getSignedBlockHeader(),
            realFulu.getKzgCommitmentsInclusionProof());

    blockRoot = realFulu.getSignedBlockHeader().getMessage().hashTreeRoot();
    validatedHeaders.put(blockRoot, cachedHeader);

    // Happy-path mock defaults
    when(gossipValidationHelper.isSlotFromFuture(slot)).thenReturn(false);
    when(gossipValidationHelper.isSlotFinalized(slot)).thenReturn(false);
  }

  @Test
  void ignore_whenNoHeaderCached() {
    validatedHeaders.clear();
    final PartialDataColumnSidecarFulu sidecar = buildEmptySidecar();
    final SafeFuture<InternalValidationResult> result =
        validator.validate(sidecar, blockRoot, columnIndex);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isIgnore);
  }

  @Test
  void ignore_whenSlotIsFromFuture() {
    when(gossipValidationHelper.isSlotFromFuture(slot)).thenReturn(true);
    final PartialDataColumnSidecarFulu sidecar = buildEmptySidecar();
    final SafeFuture<InternalValidationResult> result =
        validator.validate(sidecar, blockRoot, columnIndex);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isIgnore);
  }

  @Test
  void ignore_whenSlotIsFinalized() {
    when(gossipValidationHelper.isSlotFinalized(slot)).thenReturn(true);
    final PartialDataColumnSidecarFulu sidecar = buildEmptySidecar();
    final SafeFuture<InternalValidationResult> result =
        validator.validate(sidecar, blockRoot, columnIndex);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isIgnore);
  }

  @Test
  void reject_whenBitmapSizeMismatchesHeaderCommitments() {
    // bitmap has 1 bit but header has multiple commitments
    final PartialDataColumnSidecarSchemaFulu sidecarSchema =
        schemas.getPartialDataColumnSidecarSchema();
    // cachedHeader has e.g. 4 commitments, bitmap has 1 bit → mismatch
    final SszBitlist bitmap = sidecarSchema.getCellsPresentBitmapSchema().ofBits(1, 0);
    final PartialDataColumnSidecarFulu sidecar =
        sidecarSchema.create(
            bitmap,
            sidecarSchema
                .getPartialColumnSchema()
                .createFromElements(List.of(dataStructureUtil.randomCell())),
            sidecarSchema
                .getKzgProofsSchema()
                .createFromElements(List.of(new SszKZGProof(dataStructureUtil.randomKZGProof()))),
            sidecarSchema.getHeaderSchema().createFromElements(List.of()));

    // Only reject if the number of header commitments != 1
    if (cachedHeader.getKzgCommitments().size() != 1) {
      final SafeFuture<InternalValidationResult> result =
          validator.validate(sidecar, blockRoot, columnIndex);
      assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isReject);
    }
  }

  @Test
  void accept_whenNoCellsPresent() {
    // We use a bitmap whose SIZE equals the number of header commitments (empty but valid size)
    final int commitmentCount = cachedHeader.getKzgCommitments().size();
    final PartialDataColumnSidecarSchemaFulu sidecarSchema =
        schemas.getPartialDataColumnSidecarSchema();
    final SszBitlist correctBitmap =
        sidecarSchema.getCellsPresentBitmapSchema().ofBits(commitmentCount);
    final PartialDataColumnSidecarFulu validSidecar =
        sidecarSchema.create(
            correctBitmap,
            sidecarSchema.getPartialColumnSchema().createFromElements(List.of()),
            sidecarSchema.getKzgProofsSchema().createFromElements(List.of()),
            sidecarSchema.getHeaderSchema().createFromElements(List.of()));

    final SafeFuture<InternalValidationResult> result =
        validator.validate(validSidecar, blockRoot, columnIndex);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isAccept);
  }

  private PartialDataColumnSidecarFulu buildEmptySidecar() {
    final PartialDataColumnSidecarSchemaFulu sidecarSchema =
        schemas.getPartialDataColumnSidecarSchema();
    return sidecarSchema.create(
        sidecarSchema.getCellsPresentBitmapSchema().ofBits(0),
        sidecarSchema.getPartialColumnSchema().createFromElements(List.of()),
        sidecarSchema.getKzgProofsSchema().createFromElements(List.of()),
        sidecarSchema.getHeaderSchema().createFromElements(List.of()));
  }
}

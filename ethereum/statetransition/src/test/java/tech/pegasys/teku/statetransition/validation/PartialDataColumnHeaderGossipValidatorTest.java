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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.bls.BLSSignatureVerifier;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBytes32Vector;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszBytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.config.SpecConfigFulu;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.DataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderSchemaFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarSchemaFulu;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.type.SszKZGCommitment;
import tech.pegasys.teku.spec.logic.common.statetransition.results.BlockImportResult;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsFulu;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class PartialDataColumnHeaderGossipValidatorTest {

  private final Spec spec =
      TestSpecFactory.createMinimalFulu(b -> b.blsSignatureVerifier(BLSSignatureVerifier.NOOP));
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final SchemaDefinitionsFulu schemas =
      SchemaDefinitionsFulu.required(spec.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());
  private final SpecConfigFulu config =
      SpecConfigFulu.required(spec.forMilestone(SpecMilestone.FULU).getConfig());
  private final GossipValidationHelper gossipValidationHelper = mock(GossipValidationHelper.class);
  private final Map<Bytes32, BlockImportResult> invalidBlockRoots = new HashMap<>();
  private final Map<Bytes32, PartialDataColumnHeaderFulu> validatedHeaders = new HashMap<>();

  private PartialDataColumnHeaderGossipValidator validator;

  // Test fixture state
  private UInt64 slot;
  private UInt64 parentSlot;
  private Bytes32 parentRoot;
  private Bytes32 blockRoot;
  private PartialDataColumnSidecarFulu sidecar;

  @BeforeEach
  void setup() {
    validator =
        PartialDataColumnHeaderGossipValidator.create(
            spec, invalidBlockRoots, gossipValidationHelper, validatedHeaders);

    parentSlot = UInt64.valueOf(1);
    slot = UInt64.valueOf(2);
    parentRoot = dataStructureUtil.randomBytes32();

    final SignedBeaconBlock block =
        dataStructureUtil.randomSignedBeaconBlock(slot.longValue(), parentRoot);
    final DataColumnSidecar realSidecar =
        dataStructureUtil.randomDataColumnSidecar(block, UInt64.ZERO);
    final DataColumnSidecarFulu realSidecarFulu = DataColumnSidecarFulu.required(realSidecar);

    // Build a PartialDataColumnHeaderFulu with real inclusion proof from the sidecar
    final PartialDataColumnHeaderSchemaFulu headerSchema =
        schemas.getPartialDataColumnHeaderSchema();
    final PartialDataColumnHeaderFulu header =
        headerSchema.create(
            realSidecarFulu.getKzgCommitments(),
            realSidecarFulu.getSignedBlockHeader(),
            realSidecarFulu.getKzgCommitmentsInclusionProof());

    blockRoot = realSidecarFulu.getSignedBlockHeader().getMessage().hashTreeRoot();

    final PartialDataColumnSidecarSchemaFulu sidecarSchema =
        schemas.getPartialDataColumnSidecarSchema();
    sidecar =
        sidecarSchema.create(
            sidecarSchema.getCellsPresentBitmapSchema().ofBits(0),
            sidecarSchema.getPartialColumnSchema().createFromElements(List.of()),
            sidecarSchema.getKzgProofsSchema().createFromElements(List.of()),
            sidecarSchema.getHeaderSchema().createFromElements(List.of(header)));

    // Happy-path mock defaults
    when(gossipValidationHelper.isSlotFromFuture(slot)).thenReturn(false);
    when(gossipValidationHelper.isSlotFinalized(slot)).thenReturn(false);
    when(gossipValidationHelper.isBlockAvailable(parentRoot)).thenReturn(true);
    when(gossipValidationHelper.getSlotForBlockRoot(parentRoot))
        .thenReturn(Optional.of(parentSlot));
    when(gossipValidationHelper.currentFinalizedCheckpointIsAncestorOfBlock(slot, parentRoot))
        .thenReturn(true);
    when(gossipValidationHelper.getParentStateInBlockEpoch(parentSlot, parentRoot, slot))
        .thenReturn(
            SafeFuture.completedFuture(Optional.of(dataStructureUtil.randomBeaconState(slot))));
    when(gossipValidationHelper.isSignatureValidWithRespectToProposerIndex(
            any(), any(), any(), any()))
        .thenReturn(true);
    when(gossipValidationHelper.isProposerTheExpectedProposer(any(), any(), any()))
        .thenReturn(true);
  }

  @Test
  void accept_onValidHeader() {
    final SafeFuture<InternalValidationResult> result = validator.validate(sidecar, blockRoot);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isAccept);
  }

  @Test
  void reject_whenNoHeaderPresent() {
    final PartialDataColumnSidecarSchemaFulu sidecarSchema =
        schemas.getPartialDataColumnSidecarSchema();
    final PartialDataColumnSidecarFulu noHeaderSidecar =
        sidecarSchema.create(
            sidecarSchema.getCellsPresentBitmapSchema().ofBits(0),
            sidecarSchema.getPartialColumnSchema().createFromElements(List.of()),
            sidecarSchema.getKzgProofsSchema().createFromElements(List.of()),
            sidecarSchema.getHeaderSchema().createFromElements(List.of()));

    final SafeFuture<InternalValidationResult> result =
        validator.validate(noHeaderSidecar, blockRoot);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isReject);
  }

  @Test
  void reject_whenBitmapCellsProofsMismatch() {
    // bitmap has 0 bits but cells have 1 entry → mismatch
    final PartialDataColumnSidecarSchemaFulu sidecarSchema =
        schemas.getPartialDataColumnSidecarSchema();
    final var header = sidecar.getOptionalHeader().orElseThrow();
    final PartialDataColumnSidecarFulu mismatchSidecar =
        sidecarSchema.create(
            sidecarSchema.getCellsPresentBitmapSchema().ofBits(1, 0), // popcount = 1
            sidecarSchema.getPartialColumnSchema().createFromElements(List.of()), // 0 cells
            sidecarSchema.getKzgProofsSchema().createFromElements(List.of()),
            sidecarSchema.getHeaderSchema().createFromElements(List.of(header)));

    final SafeFuture<InternalValidationResult> result =
        validator.validate(mismatchSidecar, blockRoot);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isReject);
  }

  @Test
  void reject_whenBlockRootMismatch() {
    final Bytes32 wrongBlockRoot = dataStructureUtil.randomBytes32();
    final SafeFuture<InternalValidationResult> result = validator.validate(sidecar, wrongBlockRoot);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isReject);
  }

  @Test
  void reject_whenKzgCommitmentsEmpty() {
    final PartialDataColumnHeaderSchemaFulu headerSchema =
        schemas.getPartialDataColumnHeaderSchema();
    final PartialDataColumnHeaderFulu emptyHeader =
        headerSchema.create(
            headerSchema.getKzgCommitmentsSchema().of(),
            dataStructureUtil.randomSignedBeaconBlockHeader(slot),
            buildRandomInclusionProof());
    final PartialDataColumnSidecarSchemaFulu sidecarSchema =
        schemas.getPartialDataColumnSidecarSchema();
    final PartialDataColumnSidecarFulu sidecarWithEmptyHeader =
        sidecarSchema.create(
            sidecarSchema.getCellsPresentBitmapSchema().ofBits(0),
            sidecarSchema.getPartialColumnSchema().createFromElements(List.of()),
            sidecarSchema.getKzgProofsSchema().createFromElements(List.of()),
            sidecarSchema.getHeaderSchema().createFromElements(List.of(emptyHeader)));
    final Bytes32 root = emptyHeader.getSignedBlockHeader().getMessage().hashTreeRoot();

    final SafeFuture<InternalValidationResult> result =
        validator.validate(sidecarWithEmptyHeader, root);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isReject);
  }

  @Test
  void reject_whenCachedHeaderDoesNotMatch() {
    // Pre-populate cache with a different header for the same blockRoot
    final PartialDataColumnHeaderSchemaFulu headerSchema =
        schemas.getPartialDataColumnHeaderSchema();
    final PartialDataColumnHeaderFulu differentHeader =
        headerSchema.create(
            headerSchema
                .getKzgCommitmentsSchema()
                .createFromElements(
                    List.of(new SszKZGCommitment(dataStructureUtil.randomKZGCommitment()))),
            dataStructureUtil.randomSignedBeaconBlockHeader(slot),
            buildRandomInclusionProof());
    validatedHeaders.put(blockRoot, differentHeader);

    final SafeFuture<InternalValidationResult> result = validator.validate(sidecar, blockRoot);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isReject);
  }

  @Test
  void ignore_whenSlotIsFromFuture() {
    when(gossipValidationHelper.isSlotFromFuture(slot)).thenReturn(true);
    final SafeFuture<InternalValidationResult> result = validator.validate(sidecar, blockRoot);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isIgnore);
  }

  @Test
  void ignore_whenSlotIsFinalized() {
    when(gossipValidationHelper.isSlotFinalized(slot)).thenReturn(true);
    final SafeFuture<InternalValidationResult> result = validator.validate(sidecar, blockRoot);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isIgnore);
  }

  @Test
  void saveForFuture_whenParentBlockNotAvailable() {
    when(gossipValidationHelper.isBlockAvailable(parentRoot)).thenReturn(false);
    final SafeFuture<InternalValidationResult> result = validator.validate(sidecar, blockRoot);
    assertThat(result).isCompletedWithValue(InternalValidationResult.SAVE_FOR_FUTURE);
  }

  @Test
  void reject_whenParentBlockIsInvalid() {
    invalidBlockRoots.put(parentRoot, BlockImportResult.FAILED_UNKNOWN_PARENT);
    final SafeFuture<InternalValidationResult> result = validator.validate(sidecar, blockRoot);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isReject);
  }

  @Test
  void reject_whenSlotNotGreaterThanParentSlot() {
    // Parent slot == block slot → not greater
    when(gossipValidationHelper.getSlotForBlockRoot(parentRoot)).thenReturn(Optional.of(slot));
    final SafeFuture<InternalValidationResult> result = validator.validate(sidecar, blockRoot);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isReject);
  }

  @Test
  void reject_whenFinalizedCheckpointNotAncestor() {
    when(gossipValidationHelper.currentFinalizedCheckpointIsAncestorOfBlock(slot, parentRoot))
        .thenReturn(false);
    final SafeFuture<InternalValidationResult> result = validator.validate(sidecar, blockRoot);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isReject);
  }

  @Test
  void ignore_whenStateUnavailableForProposerValidation() {
    when(gossipValidationHelper.getParentStateInBlockEpoch(parentSlot, parentRoot, slot))
        .thenReturn(SafeFuture.completedFuture(Optional.empty()));
    final SafeFuture<InternalValidationResult> result = validator.validate(sidecar, blockRoot);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isIgnore);
  }

  @Test
  void reject_whenProposerSignatureInvalid() {
    when(gossipValidationHelper.isSignatureValidWithRespectToProposerIndex(
            any(), any(), any(), any()))
        .thenReturn(false);
    final SafeFuture<InternalValidationResult> result = validator.validate(sidecar, blockRoot);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isReject);
  }

  @Test
  void reject_whenProposerNotExpected() {
    when(gossipValidationHelper.isProposerTheExpectedProposer(any(), any(), any()))
        .thenReturn(false);
    final SafeFuture<InternalValidationResult> result = validator.validate(sidecar, blockRoot);
    assertThat(result).isCompletedWithValueMatching(InternalValidationResult::isReject);
  }

  @Test
  void accept_andCachesHeader_onSuccess() {
    assertThat(validatedHeaders).doesNotContainKey(blockRoot);
    assertThat(validator.validate(sidecar, blockRoot))
        .isCompletedWithValueMatching(InternalValidationResult::isAccept);
    assertThat(validatedHeaders).containsKey(blockRoot);
  }

  private SszBytes32Vector buildRandomInclusionProof() {
    final PartialDataColumnHeaderSchemaFulu headerSchema =
        schemas.getPartialDataColumnHeaderSchema();
    final int depth = config.getKzgCommitmentsInclusionProofDepth().intValue();
    return headerSchema
        .getKzgCommitmentsInclusionProofSchema()
        .createFromElements(
            IntStream.range(0, depth)
                .mapToObj(__ -> SszBytes32.of(dataStructureUtil.randomBytes32()))
                .toList());
  }
}

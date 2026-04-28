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

package tech.pegasys.teku.spec.logic.versions.fulu.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.io.Resources;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitlist;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.kzg.KZG;
import tech.pegasys.teku.kzg.KZGCommitment;
import tech.pegasys.teku.kzg.trusted_setups.TrustedSetupLoader;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.Cell;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.DataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarSchemaFulu;
import tech.pegasys.teku.spec.datastructures.type.SszKZGCommitment;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;
import tech.pegasys.teku.spec.logic.common.statetransition.availability.AvailabilityCheckerFactory;
import tech.pegasys.teku.spec.logic.common.util.AbstractPartialDataColumnSidecarUtilTest;
import tech.pegasys.teku.spec.logic.common.util.PartialDataColumnSidecarUtil;
import tech.pegasys.teku.spec.logic.versions.fulu.helpers.MiscHelpersFulu;
import tech.pegasys.teku.spec.logic.versions.fulu.helpers.MiscHelpersFuluTest;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsFulu;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class PartialDataColumnSidecarUtilFuluTest extends AbstractPartialDataColumnSidecarUtilTest {

  private static final Spec SPEC =
      TestSpecFactory.createMinimalFulu(
          builder ->
              builder.fuluBuilder(
                  fuluBuilder ->
                      fuluBuilder
                          .cellsPerExtBlob(128)
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

  @Override
  protected Spec createSpec() {
    return SPEC;
  }

  @Override
  protected PartialDataColumnSidecarUtil createUtil(final Spec spec) {
    final MiscHelpersFulu miscHelpers =
        MiscHelpersFulu.required(spec.forMilestone(SpecMilestone.FULU).miscHelpers());
    return new PartialDataColumnSidecarUtilFulu(miscHelpers);
  }

  @Test
  void verifyKzgProofs_allCellsPresent_returnsTrue() throws Exception {
    final SchemaDefinitionsFulu schemas =
        SchemaDefinitionsFulu.required(
            SPEC.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());
    final PartialDataColumnSidecarSchemaFulu partialSchema =
        schemas.getPartialDataColumnSidecarSchema();

    final byte[] sidecarSsz =
        Resources.toByteArray(Resources.getResource(MiscHelpersFuluTest.class, "sidecar.ssz"));
    final DataColumnSidecar sidecar =
        schemas.getDataColumnSidecarSchema().sszDeserialize(Bytes.wrap(sidecarSsz));
    final DataColumnSidecarFulu sidecarFulu = DataColumnSidecarFulu.required(sidecar);
    final int numCells = sidecarFulu.getColumn().size();

    final int[] allBits = IntStream.range(0, numCells).toArray();
    final SszBitlist bitmap = partialSchema.getCellsPresentBitmapSchema().ofBits(numCells, allBits);
    final SszList<Cell> cells =
        partialSchema
            .getPartialColumnSchema()
            .createFromElements(sidecarFulu.getColumn().stream().toList());
    final SszList<SszKZGProof> proofs =
        partialSchema
            .getKzgProofsSchema()
            .createFromElements(sidecarFulu.getKzgProofs().stream().toList());
    final PartialDataColumnSidecarFulu partialSidecar =
        partialSchema.create(
            bitmap, cells, proofs, partialSchema.getHeaderSchema().createFromElements(List.of()));

    final List<KZGCommitment> commitments =
        sidecarFulu.getKzgCommitments().stream().map(SszKZGCommitment::getKZGCommitment).toList();

    final PartialDataColumnSidecarUtil util = createUtil(SPEC);
    assertThat(util.verifyKzgProofs(partialSidecar, commitments, sidecar.getIndex().intValue()))
        .isTrue();
  }

  @Test
  void getPartialDataColumnSidecarUtil_accessibleViaSpec() {
    assertThat(SPEC.getPartialDataColumnSidecarUtil(UInt64.ZERO))
        .isNotNull()
        .isInstanceOf(PartialDataColumnSidecarUtilFulu.class);
  }

  @Test
  void verifyHeaderInclusionProof_nonEmptyCommitments_delegatesToMiscHelpers() {
    final DataStructureUtil dataStructureUtil = new DataStructureUtil(SPEC);
    final SchemaDefinitionsFulu schemas =
        SchemaDefinitionsFulu.required(
            SPEC.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());
    final PartialDataColumnSidecarUtil util = createUtil(SPEC);

    final SszList<SszKZGCommitment> commitments =
        schemas
            .getPartialDataColumnHeaderSchema()
            .getKzgCommitmentsSchema()
            .createFromElements(
                List.of(new SszKZGCommitment(dataStructureUtil.randomKZGCommitment())));
    final var header =
        buildHeader(
            SPEC,
            commitments,
            dataStructureUtil.randomSignedBeaconBlockHeader(),
            dataStructureUtil);

    // With a random header the Merkle proof won't validate — but it returns false (not throws)
    assertThat(util.verifyHeaderInclusionProof(header)).isFalse();
  }
}

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

package tech.pegasys.teku.spec.logic.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitlist;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszBytes32;
import tech.pegasys.teku.kzg.KZGCommitment;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.config.SpecConfigFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderSchemaFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarSchemaFulu;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlockHeader;
import tech.pegasys.teku.spec.datastructures.type.SszKZGCommitment;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsFulu;
import tech.pegasys.teku.spec.util.DataStructureUtil;

/** Abstract base for testing PartialDataColumnSidecarUtil implementations. */
public abstract class AbstractPartialDataColumnSidecarUtilTest {

  protected abstract Spec createSpec();

  protected abstract PartialDataColumnSidecarUtil createUtil(Spec spec);

  @Test
  void verifyHeaderInclusionProof_emptyCommitments_returnsFalse() {
    final Spec spec = createSpec();
    final PartialDataColumnSidecarUtil util = createUtil(spec);
    final SchemaDefinitionsFulu schemas =
        SchemaDefinitionsFulu.required(
            spec.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());
    final SpecConfigFulu config =
        SpecConfigFulu.required(spec.forMilestone(SpecMilestone.FULU).getConfig());
    final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);

    final PartialDataColumnHeaderSchemaFulu headerSchema =
        schemas.getPartialDataColumnHeaderSchema();
    final PartialDataColumnHeaderFulu header =
        headerSchema.create(
            headerSchema.getKzgCommitmentsSchema().of(),
            dataStructureUtil.randomSignedBeaconBlockHeader(),
            headerSchema
                .getKzgCommitmentsInclusionProofSchema()
                .createFromElements(
                    IntStream.range(0, config.getKzgCommitmentsInclusionProofDepth().intValue())
                        .mapToObj(__ -> SszBytes32.of(dataStructureUtil.randomBytes32()))
                        .toList()));

    assertThat(util.verifyHeaderInclusionProof(header)).isFalse();
  }

  @Test
  void verifyKzgProofs_noCellsPresent_returnsTrue() {
    final Spec spec = createSpec();
    final PartialDataColumnSidecarUtil util = createUtil(spec);
    final SchemaDefinitionsFulu schemas =
        SchemaDefinitionsFulu.required(
            spec.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());

    final PartialDataColumnSidecarSchemaFulu sidecarSchema =
        schemas.getPartialDataColumnSidecarSchema();
    final PartialDataColumnSidecarFulu sidecar =
        sidecarSchema.create(
            sidecarSchema.getCellsPresentBitmapSchema().ofBits(0),
            sidecarSchema.getPartialColumnSchema().createFromElements(List.of()),
            sidecarSchema.getKzgProofsSchema().createFromElements(List.of()),
            sidecarSchema.getHeaderSchema().createFromElements(List.of()));

    assertThat(util.verifyKzgProofs(sidecar, List.of(), 0)).isTrue();
  }

  protected PartialDataColumnHeaderFulu buildHeader(
      final Spec spec,
      final SszList<SszKZGCommitment> kzgCommitments,
      final SignedBeaconBlockHeader signedBlockHeader,
      final DataStructureUtil dataStructureUtil) {
    final SchemaDefinitionsFulu schemas =
        SchemaDefinitionsFulu.required(
            spec.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());
    final SpecConfigFulu config =
        SpecConfigFulu.required(spec.forMilestone(SpecMilestone.FULU).getConfig());
    final PartialDataColumnHeaderSchemaFulu headerSchema =
        schemas.getPartialDataColumnHeaderSchema();
    return headerSchema.create(
        kzgCommitments,
        signedBlockHeader,
        headerSchema
            .getKzgCommitmentsInclusionProofSchema()
            .createFromElements(
                IntStream.range(0, config.getKzgCommitmentsInclusionProofDepth().intValue())
                    .mapToObj(__ -> SszBytes32.of(dataStructureUtil.randomBytes32()))
                    .toList()));
  }

  protected PartialDataColumnSidecarFulu buildPartialSidecar(
      final Spec spec, final SszBitlist bitmap, final List<SszKZGProof> proofs) {
    final SchemaDefinitionsFulu schemas =
        SchemaDefinitionsFulu.required(
            spec.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());
    final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
    final PartialDataColumnSidecarSchemaFulu sidecarSchema =
        schemas.getPartialDataColumnSidecarSchema();
    return sidecarSchema.create(
        bitmap,
        sidecarSchema
            .getPartialColumnSchema()
            .createFromElements(
                IntStream.range(0, bitmap.getBitCount())
                    .mapToObj(__ -> dataStructureUtil.randomCell())
                    .toList()),
        sidecarSchema.getKzgProofsSchema().createFromElements(proofs),
        sidecarSchema.getHeaderSchema().createFromElements(List.of()));
  }

  protected List<KZGCommitment> randomCommitments(final Spec spec, final int count) {
    final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
    return IntStream.range(0, count)
        .mapToObj(__ -> dataStructureUtil.randomKZGCommitment())
        .toList();
  }
}

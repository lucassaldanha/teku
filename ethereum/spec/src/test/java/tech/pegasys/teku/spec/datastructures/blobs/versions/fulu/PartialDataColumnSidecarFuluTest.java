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

package tech.pegasys.teku.spec.datastructures.blobs.versions.fulu;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.pegasys.teku.infrastructure.ssz.SszDataAssert.assertThatSszData;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitlist;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBytes32Vector;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszBytes32;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.config.SpecConfigFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnPartsMetadata.PartialDataColumnPartsMetadataSchema;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlockHeader;
import tech.pegasys.teku.spec.datastructures.type.SszKZGCommitment;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsFulu;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class PartialDataColumnSidecarFuluTest {

  private final Spec spec = TestSpecFactory.createMinimalFulu();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final SchemaDefinitionsFulu schemaDefinitions =
      SchemaDefinitionsFulu.required(spec.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());
  private final SpecConfigFulu config =
      SpecConfigFulu.required(spec.forMilestone(SpecMilestone.FULU).getConfig());

  @Test
  void partialDataColumnPartsMetadata_roundTrip() {
    final PartialDataColumnPartsMetadataSchema schema =
        schemaDefinitions.getPartialDataColumnPartsMetadataSchema();
    final SszBitlist available = schema.getAvailableSchema().ofBits(4, 0, 2, 3);
    final SszBitlist requests = schema.getRequestsSchema().ofBits(4, 1);
    final PartialDataColumnPartsMetadata metadata = schema.create(available, requests);

    final PartialDataColumnPartsMetadata deserialized =
        schema.sszDeserialize(metadata.sszSerialize());

    assertThatSszData(deserialized).isEqualByAllMeansTo(metadata);
    assertThat(deserialized.getAvailable()).isEqualTo(available);
    assertThat(deserialized.getRequests()).isEqualTo(requests);
  }

  @Test
  void partialDataColumnPartsMetadata_emptyBitlists_roundTrip() {
    final PartialDataColumnPartsMetadataSchema schema =
        schemaDefinitions.getPartialDataColumnPartsMetadataSchema();
    final SszBitlist available = schema.getAvailableSchema().ofBits(0);
    final SszBitlist requests = schema.getRequestsSchema().ofBits(0);
    final PartialDataColumnPartsMetadata metadata = schema.create(available, requests);

    final PartialDataColumnPartsMetadata deserialized =
        schema.sszDeserialize(metadata.sszSerialize());

    assertThatSszData(deserialized).isEqualByAllMeansTo(metadata);
  }

  @Test
  void partialDataColumnHeaderFulu_roundTrip() {
    final PartialDataColumnHeaderFulu header = randomHeader();
    final PartialDataColumnHeaderSchemaFulu schema =
        schemaDefinitions.getPartialDataColumnHeaderSchema();

    final PartialDataColumnHeaderFulu deserialized = schema.sszDeserialize(header.sszSerialize());

    assertThatSszData(deserialized).isEqualByAllMeansTo(header);
    assertThat(deserialized.getKzgCommitments().size())
        .isEqualTo(header.getKzgCommitments().size());
    assertThat(deserialized.getSignedBlockHeader()).isEqualTo(header.getSignedBlockHeader());
    assertThat(deserialized.getKzgCommitmentsInclusionProof())
        .isEqualTo(header.getKzgCommitmentsInclusionProof());
  }

  @Test
  void partialDataColumnSidecarFulu_withHeader_roundTrip() {
    final PartialDataColumnSidecarFulu sidecar = randomPartialSidecar(true);
    final PartialDataColumnSidecarSchemaFulu schema =
        schemaDefinitions.getPartialDataColumnSidecarSchema();

    final PartialDataColumnSidecarFulu deserialized = schema.sszDeserialize(sidecar.sszSerialize());

    assertThatSszData(deserialized).isEqualByAllMeansTo(sidecar);
    assertThat(deserialized.getOptionalHeader()).isPresent();
    assertThat(deserialized.getCellsPresentBitmap()).isEqualTo(sidecar.getCellsPresentBitmap());
    assertThat(deserialized.getPartialColumn().size()).isEqualTo(sidecar.getPartialColumn().size());
    assertThat(deserialized.getKzgProofs().size()).isEqualTo(sidecar.getKzgProofs().size());
  }

  @Test
  void partialDataColumnSidecarFulu_withoutHeader_roundTrip() {
    final PartialDataColumnSidecarFulu sidecar = randomPartialSidecar(false);
    final PartialDataColumnSidecarSchemaFulu schema =
        schemaDefinitions.getPartialDataColumnSidecarSchema();

    final PartialDataColumnSidecarFulu deserialized = schema.sszDeserialize(sidecar.sszSerialize());

    assertThatSszData(deserialized).isEqualByAllMeansTo(sidecar);
    assertThat(deserialized.getOptionalHeader()).isEqualTo(Optional.empty());
  }

  @Test
  void partialDataColumnSidecarFulu_emptyCells_roundTrip() {
    final PartialDataColumnSidecarSchemaFulu schema =
        schemaDefinitions.getPartialDataColumnSidecarSchema();
    final SszBitlist emptyBitmap = schema.getCellsPresentBitmapSchema().ofBits(0);
    final SszList<Cell> emptyColumn = schema.getPartialColumnSchema().createFromElements(List.of());
    final SszList<SszKZGProof> emptyProofs =
        schema.getKzgProofsSchema().createFromElements(List.of());
    final SszList<PartialDataColumnHeaderFulu> emptyHeader =
        schema.getHeaderSchema().createFromElements(List.of());

    final PartialDataColumnSidecarFulu sidecar =
        schema.create(emptyBitmap, emptyColumn, emptyProofs, emptyHeader);
    final PartialDataColumnSidecarFulu deserialized = schema.sszDeserialize(sidecar.sszSerialize());

    assertThatSszData(deserialized).isEqualByAllMeansTo(sidecar);
    assertThat(deserialized.getCellsPresentBitmap().getBitCount()).isZero();
    assertThat(deserialized.getPartialColumn().isEmpty()).isTrue();
    assertThat(deserialized.getKzgProofs().isEmpty()).isTrue();
    assertThat(deserialized.getOptionalHeader()).isEmpty();
  }

  private PartialDataColumnHeaderFulu randomHeader() {
    final PartialDataColumnHeaderSchemaFulu schema =
        schemaDefinitions.getPartialDataColumnHeaderSchema();
    final int numCommitments = 3;
    final SszList<SszKZGCommitment> kzgCommitments =
        schema
            .getKzgCommitmentsSchema()
            .createFromElements(
                IntStream.range(0, numCommitments)
                    .mapToObj(__ -> new SszKZGCommitment(dataStructureUtil.randomKZGCommitment()))
                    .toList());
    final SignedBeaconBlockHeader signedBlockHeader =
        dataStructureUtil.randomSignedBeaconBlockHeader();
    final int proofDepth = config.getKzgCommitmentsInclusionProofDepth().intValue();
    final List<Bytes32> inclusionProofElements =
        IntStream.range(0, proofDepth).mapToObj(__ -> dataStructureUtil.randomBytes32()).toList();
    final SszBytes32Vector inclusionProof =
        schema
            .getKzgCommitmentsInclusionProofSchema()
            .createFromElements(inclusionProofElements.stream().map(SszBytes32::of).toList());
    return schema.create(kzgCommitments, signedBlockHeader, inclusionProof);
  }

  private PartialDataColumnSidecarFulu randomPartialSidecar(final boolean includeHeader) {
    final PartialDataColumnSidecarSchemaFulu schema =
        schemaDefinitions.getPartialDataColumnSidecarSchema();
    final int numCells = 2;
    final SszBitlist bitmap = schema.getCellsPresentBitmapSchema().ofBits(numCells, 0, 1);
    final SszList<Cell> cells =
        schema
            .getPartialColumnSchema()
            .createFromElements(
                IntStream.range(0, numCells)
                    .mapToObj(__ -> dataStructureUtil.randomCell())
                    .toList());
    final SszList<SszKZGProof> proofs =
        schema
            .getKzgProofsSchema()
            .createFromElements(
                IntStream.range(0, numCells)
                    .mapToObj(__ -> new SszKZGProof(dataStructureUtil.randomKZGProof()))
                    .toList());
    final SszList<PartialDataColumnHeaderFulu> headerList =
        includeHeader
            ? schema.getHeaderSchema().createFromElements(List.of(randomHeader()))
            : schema.getHeaderSchema().createFromElements(List.of());
    return schema.create(bitmap, cells, proofs, headerList);
  }
}

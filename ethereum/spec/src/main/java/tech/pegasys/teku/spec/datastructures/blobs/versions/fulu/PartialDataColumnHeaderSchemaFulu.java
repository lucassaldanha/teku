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

import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBytes32Vector;
import tech.pegasys.teku.infrastructure.ssz.containers.ContainerSchema3;
import tech.pegasys.teku.infrastructure.ssz.schema.SszFieldName;
import tech.pegasys.teku.infrastructure.ssz.schema.SszListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszBytes32VectorSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.spec.config.SpecConfigFulu;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlockHeader;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlockHeaderSchema;
import tech.pegasys.teku.spec.datastructures.type.SszKZGCommitment;
import tech.pegasys.teku.spec.datastructures.type.SszKZGCommitmentSchema;

public class PartialDataColumnHeaderSchemaFulu
    extends ContainerSchema3<
        PartialDataColumnHeaderFulu,
        SszList<SszKZGCommitment>,
        SignedBeaconBlockHeader,
        SszBytes32Vector> {

  public static final SszFieldName FIELD_KZG_COMMITMENTS = () -> "kzg_commitments";
  public static final SszFieldName FIELD_SIGNED_BLOCK_HEADER = () -> "signed_block_header";
  public static final SszFieldName FIELD_KZG_COMMITMENTS_INCLUSION_PROOF =
      () -> "kzg_commitments_inclusion_proof";

  public PartialDataColumnHeaderSchemaFulu(
      final SignedBeaconBlockHeaderSchema signedBeaconBlockHeaderSchema,
      final SpecConfigFulu specConfig) {
    super(
        "PartialDataColumnHeaderFulu",
        namedSchema(
            FIELD_KZG_COMMITMENTS,
            SszListSchema.create(
                SszKZGCommitmentSchema.INSTANCE, specConfig.getMaxBlobCommitmentsPerBlock())),
        namedSchema(FIELD_SIGNED_BLOCK_HEADER, signedBeaconBlockHeaderSchema),
        namedSchema(
            FIELD_KZG_COMMITMENTS_INCLUSION_PROOF,
            SszBytes32VectorSchema.create(
                specConfig.getKzgCommitmentsInclusionProofDepth().intValue())));
  }

  @SuppressWarnings("unchecked")
  public SszListSchema<SszKZGCommitment, ?> getKzgCommitmentsSchema() {
    return (SszListSchema<SszKZGCommitment, ?>)
        getChildSchema(getFieldIndex(FIELD_KZG_COMMITMENTS));
  }

  public SszBytes32VectorSchema<?> getKzgCommitmentsInclusionProofSchema() {
    return (SszBytes32VectorSchema<?>)
        getChildSchema(getFieldIndex(FIELD_KZG_COMMITMENTS_INCLUSION_PROOF));
  }

  public PartialDataColumnHeaderFulu create(
      final SszList<SszKZGCommitment> kzgCommitments,
      final SignedBeaconBlockHeader signedBlockHeader,
      final SszBytes32Vector kzgCommitmentsInclusionProof) {
    return new PartialDataColumnHeaderFulu(
        this, kzgCommitments, signedBlockHeader, kzgCommitmentsInclusionProof);
  }

  @Override
  public PartialDataColumnHeaderFulu createFromBackingNode(final TreeNode node) {
    return new PartialDataColumnHeaderFulu(this, node);
  }
}

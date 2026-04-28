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
import tech.pegasys.teku.infrastructure.ssz.containers.Container3;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlockHeader;
import tech.pegasys.teku.spec.datastructures.type.SszKZGCommitment;

public class PartialDataColumnHeaderFulu
    extends Container3<
        PartialDataColumnHeaderFulu,
        SszList<SszKZGCommitment>,
        SignedBeaconBlockHeader,
        SszBytes32Vector> {

  PartialDataColumnHeaderFulu(
      final PartialDataColumnHeaderSchemaFulu schema, final TreeNode backingNode) {
    super(schema, backingNode);
  }

  PartialDataColumnHeaderFulu(
      final PartialDataColumnHeaderSchemaFulu schema,
      final SszList<SszKZGCommitment> kzgCommitments,
      final SignedBeaconBlockHeader signedBlockHeader,
      final SszBytes32Vector kzgCommitmentsInclusionProof) {
    super(schema, kzgCommitments, signedBlockHeader, kzgCommitmentsInclusionProof);
  }

  @Override
  public PartialDataColumnHeaderSchemaFulu getSchema() {
    return (PartialDataColumnHeaderSchemaFulu) super.getSchema();
  }

  public SszList<SszKZGCommitment> getKzgCommitments() {
    return getField0();
  }

  public SignedBeaconBlockHeader getSignedBlockHeader() {
    return getField1();
  }

  public SszBytes32Vector getKzgCommitmentsInclusionProof() {
    return getField2();
  }
}

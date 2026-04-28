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

import java.util.Optional;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitlist;
import tech.pegasys.teku.infrastructure.ssz.containers.Container4;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;

public class PartialDataColumnSidecarFulu
    extends Container4<
        PartialDataColumnSidecarFulu,
        SszBitlist,
        SszList<Cell>,
        SszList<SszKZGProof>,
        SszList<PartialDataColumnHeaderFulu>> {

  PartialDataColumnSidecarFulu(
      final PartialDataColumnSidecarSchemaFulu schema, final TreeNode backingNode) {
    super(schema, backingNode);
  }

  PartialDataColumnSidecarFulu(
      final PartialDataColumnSidecarSchemaFulu schema,
      final SszBitlist cellsPresentBitmap,
      final SszList<Cell> partialColumn,
      final SszList<SszKZGProof> kzgProofs,
      final SszList<PartialDataColumnHeaderFulu> header) {
    super(schema, cellsPresentBitmap, partialColumn, kzgProofs, header);
  }

  @Override
  public PartialDataColumnSidecarSchemaFulu getSchema() {
    return (PartialDataColumnSidecarSchemaFulu) super.getSchema();
  }

  public SszBitlist getCellsPresentBitmap() {
    return getField0();
  }

  public SszList<Cell> getPartialColumn() {
    return getField1();
  }

  public SszList<SszKZGProof> getKzgProofs() {
    return getField2();
  }

  public SszList<PartialDataColumnHeaderFulu> getHeader() {
    return getField3();
  }

  public Optional<PartialDataColumnHeaderFulu> getOptionalHeader() {
    final SszList<PartialDataColumnHeaderFulu> headerList = getField3();
    return headerList.isEmpty() ? Optional.empty() : Optional.of(headerList.get(0));
  }
}

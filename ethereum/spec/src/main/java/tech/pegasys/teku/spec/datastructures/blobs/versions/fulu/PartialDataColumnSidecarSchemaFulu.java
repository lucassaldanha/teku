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
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitlist;
import tech.pegasys.teku.infrastructure.ssz.containers.ContainerSchema4;
import tech.pegasys.teku.infrastructure.ssz.schema.SszFieldName;
import tech.pegasys.teku.infrastructure.ssz.schema.SszListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszBitlistSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.spec.config.SpecConfigFulu;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProofSchema;

public class PartialDataColumnSidecarSchemaFulu
    extends ContainerSchema4<
        PartialDataColumnSidecarFulu,
        SszBitlist,
        SszList<Cell>,
        SszList<SszKZGProof>,
        SszList<PartialDataColumnHeaderFulu>> {

  public static final SszFieldName FIELD_CELLS_PRESENT_BITMAP = () -> "cells_present_bitmap";
  public static final SszFieldName FIELD_PARTIAL_COLUMN = () -> "partial_column";
  public static final SszFieldName FIELD_KZG_PROOFS = () -> "kzg_proofs";
  public static final SszFieldName FIELD_HEADER = () -> "header";

  public PartialDataColumnSidecarSchemaFulu(
      final CellSchema cellSchema,
      final PartialDataColumnHeaderSchemaFulu headerSchema,
      final SpecConfigFulu specConfig) {
    super(
        "PartialDataColumnSidecarFulu",
        namedSchema(
            FIELD_CELLS_PRESENT_BITMAP,
            SszBitlistSchema.create(specConfig.getMaxBlobCommitmentsPerBlock())),
        namedSchema(
            FIELD_PARTIAL_COLUMN,
            SszListSchema.create(cellSchema, specConfig.getMaxBlobCommitmentsPerBlock())),
        namedSchema(
            FIELD_KZG_PROOFS,
            SszListSchema.create(
                SszKZGProofSchema.INSTANCE, specConfig.getMaxBlobCommitmentsPerBlock())),
        namedSchema(FIELD_HEADER, SszListSchema.create(headerSchema, 1)));
  }

  public SszBitlistSchema<?> getCellsPresentBitmapSchema() {
    return (SszBitlistSchema<?>) getChildSchema(getFieldIndex(FIELD_CELLS_PRESENT_BITMAP));
  }

  @SuppressWarnings("unchecked")
  public SszListSchema<Cell, ?> getPartialColumnSchema() {
    return (SszListSchema<Cell, ?>) getChildSchema(getFieldIndex(FIELD_PARTIAL_COLUMN));
  }

  @SuppressWarnings("unchecked")
  public SszListSchema<SszKZGProof, ?> getKzgProofsSchema() {
    return (SszListSchema<SszKZGProof, ?>) getChildSchema(getFieldIndex(FIELD_KZG_PROOFS));
  }

  @SuppressWarnings("unchecked")
  public SszListSchema<PartialDataColumnHeaderFulu, ?> getHeaderSchema() {
    return (SszListSchema<PartialDataColumnHeaderFulu, ?>)
        getChildSchema(getFieldIndex(FIELD_HEADER));
  }

  public PartialDataColumnSidecarFulu create(
      final SszBitlist cellsPresentBitmap,
      final SszList<Cell> partialColumn,
      final SszList<SszKZGProof> kzgProofs,
      final SszList<PartialDataColumnHeaderFulu> header) {
    return new PartialDataColumnSidecarFulu(
        this, cellsPresentBitmap, partialColumn, kzgProofs, header);
  }

  @Override
  public PartialDataColumnSidecarFulu createFromBackingNode(final TreeNode node) {
    return new PartialDataColumnSidecarFulu(this, node);
  }
}

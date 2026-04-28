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

import tech.pegasys.teku.infrastructure.ssz.collections.SszBitlist;
import tech.pegasys.teku.infrastructure.ssz.containers.Container2;
import tech.pegasys.teku.infrastructure.ssz.containers.ContainerSchema2;
import tech.pegasys.teku.infrastructure.ssz.schema.SszFieldName;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszBitlistSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.spec.config.SpecConfigFulu;

public class PartialDataColumnPartsMetadata
    extends Container2<PartialDataColumnPartsMetadata, SszBitlist, SszBitlist> {

  public static final SszFieldName FIELD_AVAILABLE = () -> "available";
  public static final SszFieldName FIELD_REQUESTS = () -> "requests";

  public static class PartialDataColumnPartsMetadataSchema
      extends ContainerSchema2<PartialDataColumnPartsMetadata, SszBitlist, SszBitlist> {

    public PartialDataColumnPartsMetadataSchema(final SpecConfigFulu specConfig) {
      super(
          "PartialDataColumnPartsMetadata",
          namedSchema(
              FIELD_AVAILABLE, SszBitlistSchema.create(specConfig.getMaxBlobCommitmentsPerBlock())),
          namedSchema(
              FIELD_REQUESTS, SszBitlistSchema.create(specConfig.getMaxBlobCommitmentsPerBlock())));
    }

    public SszBitlistSchema<?> getAvailableSchema() {
      return (SszBitlistSchema<?>) getChildSchema(getFieldIndex(FIELD_AVAILABLE));
    }

    public SszBitlistSchema<?> getRequestsSchema() {
      return (SszBitlistSchema<?>) getChildSchema(getFieldIndex(FIELD_REQUESTS));
    }

    public PartialDataColumnPartsMetadata create(
        final SszBitlist available, final SszBitlist requests) {
      return new PartialDataColumnPartsMetadata(this, available, requests);
    }

    @Override
    public PartialDataColumnPartsMetadata createFromBackingNode(final TreeNode node) {
      return new PartialDataColumnPartsMetadata(this, node);
    }
  }

  private PartialDataColumnPartsMetadata(
      final PartialDataColumnPartsMetadataSchema schema, final TreeNode backingNode) {
    super(schema, backingNode);
  }

  public PartialDataColumnPartsMetadata(
      final PartialDataColumnPartsMetadataSchema schema,
      final SszBitlist available,
      final SszBitlist requests) {
    super(schema, available, requests);
  }

  @Override
  public PartialDataColumnPartsMetadataSchema getSchema() {
    return (PartialDataColumnPartsMetadataSchema) super.getSchema();
  }

  public SszBitlist getAvailable() {
    return getField0();
  }

  public SszBitlist getRequests() {
    return getField1();
  }
}

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

package tech.pegasys.teku.networking.eth2.gossip.waggle;

import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.ssz.collections.SszUInt64List;
import tech.pegasys.teku.infrastructure.ssz.containers.Container4;
import tech.pegasys.teku.infrastructure.ssz.containers.ContainerSchema4;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszBytes32;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszUInt64;
import tech.pegasys.teku.infrastructure.ssz.schema.SszPrimitiveSchemas;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszUInt64ListSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * EIP-8334 (draft): the attestations of one committee and one attestation data root that the sender
 * holds ({@code available}) and wants from the receiver ({@code requests}), by validator index.
 * Sent as the {@code piecesMetadata} of a Waggle frame.
 *
 * <p>The {@code data_root} field is not in the EIP text at f7f0cfb. It is what Prysm's
 * implementation (sukunrt/prysm eip-8334-waggle) puts on the wire, keying a vote by (data root,
 * validator) so that a double vote can propagate, and this follows Prysm.
 */
public class CommitteeAttestationPartsMetadata
    extends Container4<
        CommitteeAttestationPartsMetadata, SszUInt64, SszBytes32, SszUInt64List, SszUInt64List> {

  public static class CommitteeAttestationPartsMetadataSchema
      extends ContainerSchema4<
          CommitteeAttestationPartsMetadata, SszUInt64, SszBytes32, SszUInt64List, SszUInt64List> {

    public CommitteeAttestationPartsMetadataSchema(final long maxValidatorsPerCommittee) {
      super(
          "CommitteeAttestationPartsMetadata",
          namedSchema("committee_index", SszPrimitiveSchemas.UINT64_SCHEMA),
          namedSchema("data_root", SszPrimitiveSchemas.BYTES32_SCHEMA),
          namedSchema("available", SszUInt64ListSchema.create(maxValidatorsPerCommittee)),
          namedSchema("requests", SszUInt64ListSchema.create(maxValidatorsPerCommittee)));
    }

    @SuppressWarnings("unchecked")
    public CommitteeAttestationPartsMetadata create(
        final UInt64 committeeIndex,
        final Bytes32 dataRoot,
        final List<UInt64> available,
        final List<UInt64> requests) {
      return new CommitteeAttestationPartsMetadata(
          this,
          SszUInt64.of(committeeIndex),
          SszBytes32.of(dataRoot),
          ((SszUInt64ListSchema<SszUInt64List>) getFieldSchema2()).of(available),
          ((SszUInt64ListSchema<SszUInt64List>) getFieldSchema3()).of(requests));
    }

    @Override
    public CommitteeAttestationPartsMetadata createFromBackingNode(final TreeNode node) {
      return new CommitteeAttestationPartsMetadata(this, node);
    }
  }

  private CommitteeAttestationPartsMetadata(
      final CommitteeAttestationPartsMetadataSchema schema, final TreeNode node) {
    super(schema, node);
  }

  private CommitteeAttestationPartsMetadata(
      final CommitteeAttestationPartsMetadataSchema schema,
      final SszUInt64 committeeIndex,
      final SszBytes32 dataRoot,
      final SszUInt64List available,
      final SszUInt64List requests) {
    super(schema, committeeIndex, dataRoot, available, requests);
  }

  public UInt64 getCommitteeIndex() {
    return getField0().get();
  }

  public Bytes32 getDataRoot() {
    return getField1().get();
  }

  public List<UInt64> getAvailable() {
    return getField2().asListUnboxed();
  }

  public List<UInt64> getRequests() {
    return getField3().asListUnboxed();
  }
}

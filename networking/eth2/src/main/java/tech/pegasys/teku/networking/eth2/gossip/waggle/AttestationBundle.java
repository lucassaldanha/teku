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
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.collections.SszUInt64List;
import tech.pegasys.teku.infrastructure.ssz.containers.Container4;
import tech.pegasys.teku.infrastructure.ssz.containers.ContainerSchema4;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszUInt64;
import tech.pegasys.teku.infrastructure.ssz.schema.SszListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszPrimitiveSchemas;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszUInt64ListSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.operations.AttestationData;
import tech.pegasys.teku.spec.datastructures.type.SszSignature;
import tech.pegasys.teku.spec.datastructures.type.SszSignatureSchema;

/**
 * EIP-8334 (draft): the attestations of one committee that share the same data. {@code
 * signatures[i]} is the signature of validator {@code attester_indices[i]}. Sent as the {@code
 * pieces} of a Waggle frame.
 */
public class AttestationBundle
    extends Container4<
        AttestationBundle, SszUInt64, AttestationData, SszUInt64List, SszList<SszSignature>> {

  /** MAX_ATTESTATIONS_PER_BUNDLE */
  public static final int MAX_ATTESTATIONS_PER_BUNDLE = 50;

  public static class AttestationBundleSchema
      extends ContainerSchema4<
          AttestationBundle, SszUInt64, AttestationData, SszUInt64List, SszList<SszSignature>> {

    public AttestationBundleSchema(final long maxValidatorsPerCommittee) {
      super(
          "AttestationBundle",
          namedSchema("committee_index", SszPrimitiveSchemas.UINT64_SCHEMA),
          namedSchema("attestation_data", AttestationData.SSZ_SCHEMA),
          namedSchema("attester_indices", SszUInt64ListSchema.create(maxValidatorsPerCommittee)),
          namedSchema(
              "signatures",
              SszListSchema.create(SszSignatureSchema.INSTANCE, maxValidatorsPerCommittee)));
    }

    @SuppressWarnings("unchecked")
    public AttestationBundle create(
        final UInt64 committeeIndex,
        final AttestationData data,
        final List<UInt64> attesterIndices,
        final List<BLSSignature> signatures) {
      return new AttestationBundle(
          this,
          SszUInt64.of(committeeIndex),
          data,
          ((SszUInt64ListSchema<SszUInt64List>) getFieldSchema2()).of(attesterIndices),
          ((SszListSchema<SszSignature, SszList<SszSignature>>) getFieldSchema3())
              .createFromElements(signatures.stream().map(SszSignature::new).toList()));
    }

    @Override
    public AttestationBundle createFromBackingNode(final TreeNode node) {
      return new AttestationBundle(this, node);
    }
  }

  private AttestationBundle(final AttestationBundleSchema schema, final TreeNode node) {
    super(schema, node);
  }

  private AttestationBundle(
      final AttestationBundleSchema schema,
      final SszUInt64 committeeIndex,
      final AttestationData data,
      final SszUInt64List attesterIndices,
      final SszList<SszSignature> signatures) {
    super(schema, committeeIndex, data, attesterIndices, signatures);
  }

  public UInt64 getCommitteeIndex() {
    return getField0().get();
  }

  public AttestationData getData() {
    return getField1();
  }

  public List<UInt64> getAttesterIndices() {
    return getField2().asListUnboxed();
  }

  public List<BLSSignature> getSignatures() {
    return getField3().stream().map(SszSignature::getSignature).toList();
  }
}

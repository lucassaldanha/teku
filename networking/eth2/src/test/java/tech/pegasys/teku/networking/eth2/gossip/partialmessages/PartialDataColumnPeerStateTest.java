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

package tech.pegasys.teku.networking.eth2.gossip.partialmessages;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.BitSet;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnPartsMetadata;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnPartsMetadata.PartialDataColumnPartsMetadataSchema;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsFulu;

class PartialDataColumnPeerStateTest {

  private final Spec spec = TestSpecFactory.createMinimalFulu();
  private final PartialDataColumnPartsMetadataSchema metadataSchema =
      SchemaDefinitionsFulu.required(spec.forMilestone(SpecMilestone.FULU).getSchemaDefinitions())
          .getPartialDataColumnPartsMetadataSchema();

  @Test
  void initial_isFirstMessage_andHasEmptyBitSets() {
    final PartialDataColumnPeerState state = PartialDataColumnPeerState.initial();
    assertThat(state.isFirstMessage()).isTrue();
    assertThat(state.lastSeenMetadata()).isNull();
    assertThat(state.cellsSentToPeer().isEmpty()).isTrue();
    assertThat(state.cellsRequestedByPeer().isEmpty()).isTrue();
    assertThat(state.lastFirstDeliveryRewardTimeMillis()).isEmpty();
  }

  @Test
  void withUpdatedMetadata_setsMetadataAndMergesRequests() {
    final PartialDataColumnPartsMetadata metadata =
        metadataSchema.create(
            metadataSchema.getAvailableSchema().ofBits(4, 0, 1), // peer has bits 0, 1
            metadataSchema.getRequestsSchema().ofBits(4, 2, 3) // peer wants bits 2, 3
            );

    final PartialDataColumnPeerState updated =
        PartialDataColumnPeerState.initial().withUpdatedMetadata(metadata);

    assertThat(updated.isFirstMessage()).isFalse();
    assertThat(updated.lastSeenMetadata()).isEqualTo(metadata);
    // Bits 2 and 3 were requested and NOT available → should appear in cellsRequestedByPeer
    assertThat(updated.cellsRequestedByPeer().get(2)).isTrue();
    assertThat(updated.cellsRequestedByPeer().get(3)).isTrue();
    assertThat(updated.cellsRequestedByPeer().get(0)).isFalse();
  }

  @Test
  void withUpdatedMetadata_doesNotRequestBitsThatAreAvailable() {
    // Peer says available=bit0, requests=bit0 — the available bit overrides the request
    final PartialDataColumnPartsMetadata metadata =
        metadataSchema.create(
            metadataSchema.getAvailableSchema().ofBits(2, 0), // peer has bit 0
            metadataSchema.getRequestsSchema().ofBits(2, 0) // and also "requests" bit 0 (edge case)
            );

    final PartialDataColumnPeerState updated =
        PartialDataColumnPeerState.initial().withUpdatedMetadata(metadata);

    // Since bit 0 is available, it should NOT be in cellsRequestedByPeer
    assertThat(updated.cellsRequestedByPeer().get(0)).isFalse();
  }

  @Test
  void withCellsSent_accumulatesSentBits() {
    final PartialDataColumnPeerState state = PartialDataColumnPeerState.initial();
    final BitSet sent1 = new BitSet();
    sent1.set(0);
    final PartialDataColumnPeerState after1 = state.withCellsSent(sent1);
    assertThat(after1.cellsSentToPeer().get(0)).isTrue();

    final BitSet sent2 = new BitSet();
    sent2.set(2);
    final PartialDataColumnPeerState after2 = after1.withCellsSent(sent2);
    assertThat(after2.cellsSentToPeer().get(0)).isTrue();
    assertThat(after2.cellsSentToPeer().get(2)).isTrue();
  }

  @Test
  void withFirstDeliveryRewardTime_updatesTimestamp() {
    final UInt64 now = UInt64.valueOf(1234567890L);
    final PartialDataColumnPeerState updated =
        PartialDataColumnPeerState.initial().withFirstDeliveryRewardTime(now);

    assertThat(updated.lastFirstDeliveryRewardTimeMillis()).isPresent().contains(now);
  }

  @Test
  void immutability_originalUnchanged_afterUpdate() {
    final PartialDataColumnPeerState original = PartialDataColumnPeerState.initial();
    final BitSet sent = new BitSet();
    sent.set(5);
    original.withCellsSent(sent);

    // Original should still be empty
    assertThat(original.cellsSentToPeer().isEmpty()).isTrue();
  }
}

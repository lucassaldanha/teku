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

import java.util.BitSet;
import java.util.Optional;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnPartsMetadata;

/**
 * Per-peer view of the state for a single partial-messages group (topic × blockRoot).
 *
 * <p>Instances are immutable; jvm-libp2p stores and atomically replaces them via the {@code
 * PublishAction.nextPeerState} mechanism. All updates are performed by the {@code
 * PartialDataColumnSidecarHandler} and the {@code PartialDataColumnSidecarPublisher}.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code lastSeenMetadata} — the {@link PartialDataColumnPartsMetadata} decoded from the
 *       most-recently received RPC from this peer, or {@code null} if no RPC has been received yet.
 *       A {@code null} value means we should attach the eager-push header when sending our first
 *       RPC to this peer.
 *   <li>{@code cellsSentToPeer} — bitmap of blob-indices whose cells we have already shipped to
 *       this peer (to avoid re-sending).
 *   <li>{@code cellsRequestedByPeer} — union of all {@code requests} bits that the peer has set in
 *       past metadata messages for cells it does not yet have.
 *   <li>{@code lastFirstDeliveryRewardTimeMillis} — wall-clock time (ms) of the last
 *       first-message-delivery (FMD) reward issued to this peer, or empty if none has been issued
 *       yet. Used to enforce the FMD rate-limit from §11.
 * </ul>
 */
public record PartialDataColumnPeerState(
    PartialDataColumnPartsMetadata lastSeenMetadata,
    BitSet cellsSentToPeer,
    BitSet cellsRequestedByPeer,
    Optional<UInt64> lastFirstDeliveryRewardTimeMillis) {

  /** Creates the initial state for a peer we have never exchanged a RPC with. */
  public static PartialDataColumnPeerState initial() {
    return new PartialDataColumnPeerState(null, new BitSet(), new BitSet(), Optional.empty());
  }

  /**
   * Returns {@code true} if this is the first partial RPC we have received from this peer (i.e. we
   * should send the eager-push header).
   */
  public boolean isFirstMessage() {
    return lastSeenMetadata == null;
  }

  /**
   * Returns a new state reflecting that we received metadata from the peer and updated our
   * knowledge of which cells the peer has requested.
   *
   * @param metadata the decoded metadata from the received RPC
   * @return updated state (immutable copy)
   */
  public PartialDataColumnPeerState withUpdatedMetadata(
      final PartialDataColumnPartsMetadata metadata) {
    // Merge the new requests into the existing set
    final BitSet newRequests = (BitSet) cellsRequestedByPeer.clone();
    for (int i = 0; i < metadata.getRequests().size(); i++) {
      if (metadata.getRequests().getBit(i) && !metadata.getAvailable().getBit(i)) {
        newRequests.set(i);
      }
    }
    return new PartialDataColumnPeerState(
        metadata, (BitSet) cellsSentToPeer.clone(), newRequests, lastFirstDeliveryRewardTimeMillis);
  }

  /**
   * Returns a new state recording that we have sent the given cell indices to this peer.
   *
   * @param sentBlobIndices blob indices of the cells we just sent
   * @return updated state (immutable copy)
   */
  public PartialDataColumnPeerState withCellsSent(final BitSet sentBlobIndices) {
    final BitSet newSent = (BitSet) cellsSentToPeer.clone();
    newSent.or(sentBlobIndices);
    return new PartialDataColumnPeerState(
        lastSeenMetadata,
        newSent,
        (BitSet) cellsRequestedByPeer.clone(),
        lastFirstDeliveryRewardTimeMillis);
  }

  /**
   * Returns a new state recording when the FMD reward was last issued to this peer.
   *
   * @param timeMillis current wall-clock time in milliseconds
   * @return updated state (immutable copy)
   */
  public PartialDataColumnPeerState withFirstDeliveryRewardTime(final UInt64 timeMillis) {
    return new PartialDataColumnPeerState(
        lastSeenMetadata,
        (BitSet) cellsSentToPeer.clone(),
        (BitSet) cellsRequestedByPeer.clone(),
        Optional.of(timeMillis));
  }
}

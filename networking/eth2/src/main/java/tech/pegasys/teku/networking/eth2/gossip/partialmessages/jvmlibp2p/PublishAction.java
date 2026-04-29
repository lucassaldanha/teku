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

package tech.pegasys.teku.networking.eth2.gossip.partialmessages.jvmlibp2p;

/**
 * Mirrors {@code io.libp2p.pubsub.gossip.partialmessages.PublishAction} from the jvm-libp2p
 * partial-messages feature branch.
 */
public final class PublishAction<PeerState> {
  private final byte[] partialMessage;
  private final byte[] partsMetadata;
  private final PeerState nextPeerState;
  private final Throwable error;

  public PublishAction(
      final byte[] partialMessage,
      final byte[] partsMetadata,
      final PeerState nextPeerState,
      final Throwable error) {
    this.partialMessage = partialMessage;
    this.partsMetadata = partsMetadata;
    this.nextPeerState = nextPeerState;
    this.error = error;
  }

  public byte[] partialMessage() {
    return partialMessage;
  }

  public byte[] partsMetadata() {
    return partsMetadata;
  }

  public PeerState nextPeerState() {
    return nextPeerState;
  }

  public Throwable error() {
    return error;
  }
}

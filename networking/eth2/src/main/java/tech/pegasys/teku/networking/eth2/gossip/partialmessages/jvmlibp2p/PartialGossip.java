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

import java.util.concurrent.CompletableFuture;

/**
 * Abstraction over the {@code Gossip.publishPartial()} method from the jvm-libp2p partial-messages
 * feature branch.
 *
 * <p>Once jvm-libp2p merges the feature branch, implementations can delegate directly to {@code
 * io.libp2p.pubsub.gossip.Gossip#publishPartial(String, byte[], PublishActionsFn)}.
 */
public interface PartialGossip {

  <PeerState> CompletableFuture<Void> publishPartial(
      String topic, byte[] groupId, PublishActionsFn<PeerState> actionsFn);

  /** No-op implementation for use when the feature flag is disabled. */
  PartialGossip NOOP =
      new PartialGossip() {
        @Override
        public <PeerState> CompletableFuture<Void> publishPartial(
            final String topic, final byte[] groupId, final PublishActionsFn<PeerState> actionsFn) {
          return CompletableFuture.completedFuture(null);
        }
      };
}

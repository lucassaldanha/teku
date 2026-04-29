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

import io.libp2p.core.PeerId;
import io.libp2p.pubsub.gossip.Gossip;
import io.libp2p.pubsub.gossip.partialmessages.PublishActionsFn;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import kotlin.Pair;
import kotlin.sequences.SequencesKt;

/**
 * Bridges Teku's Java-friendly {@link PartialGossip} interface to {@code
 * io.libp2p.pubsub.gossip.Gossip#publishPartial}, adapting Java {@link java.util.stream.Stream}/
 * {@link Map.Entry} to the Kotlin {@code Sequence}/{@code Pair} types that jvm-libp2p expects.
 */
public class GossipBackedPartialGossip implements PartialGossip {

  private final Gossip gossip;

  public GossipBackedPartialGossip(final Gossip gossip) {
    this.gossip = gossip;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Override
  public <PeerState> CompletableFuture<Void> publishPartial(
      final String topic,
      final byte[] groupId,
      final tech.pegasys.teku.networking.eth2.gossip.partialmessages.jvmlibp2p.PublishActionsFn<
              PeerState>
          actionsFn) {
    // Use raw PublishActionsFn to cross the Java/Kotlin generic boundary without friction.
    // PeerState is erased at runtime, so this is safe.
    final PublishActionsFn rawFn =
        (peerStates, peerRequestsPartial) -> {
          // raw Function1 returns Object; cast to Boolean explicitly
          final Function<PeerId, Boolean> javaFn =
              peerId -> (Boolean) peerRequestsPartial.invoke(peerId);
          return SequencesKt.asSequence(
              actionsFn
                  .decide(peerStates, javaFn)
                  .map(
                      e ->
                          new Pair<>(
                              ((Map.Entry<?, ?>) e).getKey(), ((Map.Entry<?, ?>) e).getValue()))
                  .iterator());
        };
    return gossip.publishPartial(topic, groupId, rawFn).thenApply(ignored -> null);
  }
}

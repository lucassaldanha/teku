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

package tech.pegasys.teku.networking.p2p.libp2p.waggle;

import io.libp2p.core.PeerId;
import io.libp2p.pubsub.waggle.Waggle;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.networking.p2p.libp2p.LibP2PNodeId;
import tech.pegasys.teku.networking.p2p.peer.NodeId;
import tech.pegasys.teku.networking.p2p.waggle.WaggleNetwork;
import tech.pegasys.teku.networking.p2p.waggle.WaggleTopicHandler;

/** {@link WaggleNetwork} on top of jvm-libp2p's raw (engine-less) Waggle topics. */
public class LibP2PWaggleNetwork implements WaggleNetwork {

  private final Waggle waggle;

  public LibP2PWaggleNetwork(final Waggle waggle) {
    this.waggle = waggle;
  }

  /** The protocol binding to register on the libp2p host. */
  public Waggle getWaggle() {
    return waggle;
  }

  @Override
  public SafeFuture<Void> subscribe(final String topic, final WaggleTopicHandler handler) {
    return SafeFuture.of(
            waggle.subscribeRaw(
                topicId(topic),
                (peer, connection, objectId, metadata, pieces) ->
                    handler.onMessage(
                        new LibP2PNodeId(peer),
                        Bytes.wrap(objectId),
                        Optional.ofNullable(metadata).map(Bytes::wrap),
                        Optional.ofNullable(pieces).map(Bytes::wrap))))
        .toVoid();
  }

  @Override
  public SafeFuture<Void> unsubscribe(final String topic) {
    return SafeFuture.of(waggle.unsubscribe(topicId(topic))).toVoid();
  }

  @Override
  public SafeFuture<Void> send(
      final String topic,
      final NodeId peer,
      final Bytes objectId,
      final Optional<Bytes> metadata,
      final Optional<Bytes> pieces) {
    return SafeFuture.of(
            waggle.send(
                topicId(topic),
                new PeerId(peer.toBytes().toArrayUnsafe()),
                objectId.toArrayUnsafe(),
                metadata.map(Bytes::toArrayUnsafe).orElse(null),
                pieces.map(Bytes::toArrayUnsafe).orElse(null)))
        .toVoid();
  }

  @Override
  public SafeFuture<Collection<NodeId>> getSubscribers(final String topic) {
    return SafeFuture.of(waggle.subscribers(topicId(topic)))
        .thenApply(peers -> List.copyOf(peers.stream().<NodeId>map(LibP2PNodeId::new).toList()));
  }

  private static byte[] topicId(final String topic) {
    return topic.getBytes(StandardCharsets.UTF_8);
  }
}

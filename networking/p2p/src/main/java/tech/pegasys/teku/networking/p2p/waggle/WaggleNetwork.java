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

package tech.pegasys.teku.networking.p2p.waggle;

import java.util.Collection;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.networking.p2p.peer.NodeId;

/**
 * Access to the Waggle pubsub protocol (libp2p/specs#732, draft) at the level of individual frames.
 * Waggle itself never interprets object ids, metadata or pieces; what to send to whom is decided by
 * the application built on top of this.
 */
public interface WaggleNetwork {

  /** Opens a subscription to the topic towards every current and future peer. */
  SafeFuture<Void> subscribe(String topic, WaggleTopicHandler handler);

  SafeFuture<Void> unsubscribe(String topic);

  /**
   * Sends one frame to a peer that is subscribed to the topic. We do not have to be subscribed
   * ourselves. Fails without queueing anything if the peer is not subscribed or its connection's
   * outbound queue is full.
   */
  SafeFuture<Void> send(
      String topic, NodeId peer, Bytes objectId, Optional<Bytes> metadata, Optional<Bytes> pieces);

  /** The peers that currently hold a subscription to the topic with us. */
  SafeFuture<Collection<NodeId>> getSubscribers(String topic);
}

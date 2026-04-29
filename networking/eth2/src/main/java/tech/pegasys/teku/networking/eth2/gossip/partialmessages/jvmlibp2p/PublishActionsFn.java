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
import io.libp2p.pubsub.gossip.partialmessages.PublishAction;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Java-friendly alternative to {@code io.libp2p.pubsub.gossip.partialmessages.PublishActionsFn}.
 * Uses Java {@link Stream}/{@link java.util.Map.Entry} instead of Kotlin Sequence/Pair so that Teku
 * code stays idiomatic Java. The bridge to the jvm-libp2p type lives in {@code
 * GossipBackedPartialGossip}.
 */
@FunctionalInterface
public interface PublishActionsFn<PeerState> {
  Stream<Map.Entry<PeerId, PublishAction<PeerState>>> decide(
      Map<PeerId, ? extends PeerState> peerStates, Function<PeerId, Boolean> peerRequestsPartial);
}

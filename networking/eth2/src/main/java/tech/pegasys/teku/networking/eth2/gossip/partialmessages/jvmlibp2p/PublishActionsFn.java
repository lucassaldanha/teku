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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Mirrors {@code io.libp2p.pubsub.gossip.partialmessages.PublishActionsFn} from the jvm-libp2p
 * partial-messages feature branch.
 *
 * <p>The {@code decide} function is called on the pubsub event thread with the current peer state
 * map and a predicate for checking whether a peer requested partial for the topic.
 */
@FunctionalInterface
public interface PublishActionsFn<PeerState> {
  Stream<Map.Entry<PeerId, PublishAction<PeerState>>> decide(
      Map<PeerId, ? extends PeerState> peerStates, Function<PeerId, Boolean> peerRequestsPartial);
}

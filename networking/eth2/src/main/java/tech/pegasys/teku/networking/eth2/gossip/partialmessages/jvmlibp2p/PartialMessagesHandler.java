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
import java.util.Collection;
import java.util.Map;
import pubsub.pb.Rpc;

/**
 * Mirrors {@code io.libp2p.pubsub.gossip.partialmessages.PartialMessagesHandler} from the
 * jvm-libp2p partial-messages feature branch.
 *
 * <p>Both callbacks run on the pubsub event thread and MUST be fast and non-blocking.
 */
public interface PartialMessagesHandler<PeerState> {

  /**
   * Called on every inbound {@link Rpc.PartialMessagesExtension} RPC. The {@code peerStates} map is
   * a live view — do not retain a reference outside this call.
   */
  void onIncomingRpc(
      PeerId from,
      Map<PeerId, ? extends PeerState> peerStates,
      Rpc.PartialMessagesExtension rpc,
      PartialMessagesPeerFeedback feedback);

  /**
   * Called once per locally-initiated group during the gossipsub heartbeat for gossip targets that
   * are partial-capable on {@code topic}.
   */
  void onEmitGossip(
      String topic,
      byte[] groupId,
      Collection<PeerId> gossipPeers,
      Map<PeerId, ? extends PeerState> peerStates,
      PartialMessagesPeerFeedback feedback);
}

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
 * Mirrors {@code io.libp2p.pubsub.gossip.partialmessages.FeedbackKind} from the jvm-libp2p
 * partial-messages feature branch. Will be replaced by the library enum once that branch is merged
 * into jvm-libp2p develop.
 */
public enum FeedbackKind {
  USEFUL,
  INVALID,
  IGNORED
}

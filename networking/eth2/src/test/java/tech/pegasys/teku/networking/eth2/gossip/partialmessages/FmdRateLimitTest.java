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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * Tests the FMD (first-message delivery) rate-limit tracking in {@link PartialDataColumnPeerState}
 * as specified in §11 of the design doc. The rate-limit prevents a peer from scoring better by
 * providing cells one at a time rather than many at once.
 */
class FmdRateLimitTest {

  private static final UInt64 FMD_RATE_LIMIT_MS = UInt64.valueOf(250);

  @Test
  void initialState_hasNoLastRewardTime() {
    final PartialDataColumnPeerState state = PartialDataColumnPeerState.initial();
    assertThat(state.lastFirstDeliveryRewardTimeMillis()).isEmpty();
  }

  @Test
  void afterFirstReward_hasRewardTime() {
    final UInt64 now = UInt64.valueOf(1000L);
    final PartialDataColumnPeerState state =
        PartialDataColumnPeerState.initial().withFirstDeliveryRewardTime(now);
    assertThat(state.lastFirstDeliveryRewardTimeMillis()).isPresent().contains(now);
  }

  @Test
  void shouldAllowReward_whenNoPriorReward() {
    final PartialDataColumnPeerState state = PartialDataColumnPeerState.initial();
    final UInt64 now = UInt64.valueOf(1000L);
    assertThat(shouldAllowFmdReward(state, now)).isTrue();
  }

  @Test
  void shouldAllowReward_whenEnoughTimeHasPassed() {
    final UInt64 firstRewardTime = UInt64.valueOf(1000L);
    final PartialDataColumnPeerState state =
        PartialDataColumnPeerState.initial().withFirstDeliveryRewardTime(firstRewardTime);
    final UInt64 now = firstRewardTime.plus(FMD_RATE_LIMIT_MS);
    assertThat(shouldAllowFmdReward(state, now)).isTrue();
  }

  @Test
  void shouldDenyReward_whenNotEnoughTimeHasPassed() {
    final UInt64 firstRewardTime = UInt64.valueOf(1000L);
    final PartialDataColumnPeerState state =
        PartialDataColumnPeerState.initial().withFirstDeliveryRewardTime(firstRewardTime);
    final UInt64 now = firstRewardTime.plus(FMD_RATE_LIMIT_MS).decrement();
    assertThat(shouldAllowFmdReward(state, now)).isFalse();
  }

  @Test
  void rewardTimeIsUpdatedOnNewReward() {
    final UInt64 t1 = UInt64.valueOf(1000L);
    final UInt64 t2 = t1.plus(FMD_RATE_LIMIT_MS);
    final PartialDataColumnPeerState after1 =
        PartialDataColumnPeerState.initial().withFirstDeliveryRewardTime(t1);
    final PartialDataColumnPeerState after2 = after1.withFirstDeliveryRewardTime(t2);
    assertThat(after2.lastFirstDeliveryRewardTimeMillis()).isPresent().contains(t2);
  }

  /**
   * Mirrors the FMD rate-limit check that the handler would perform before calling
   * feedback.reportFeedback(USEFUL): allow if no prior reward or enough time has elapsed.
   */
  private static boolean shouldAllowFmdReward(
      final PartialDataColumnPeerState state, final UInt64 nowMs) {
    return state
        .lastFirstDeliveryRewardTimeMillis()
        .map(lastTime -> nowMs.isGreaterThanOrEqualTo(lastTime.plus(FMD_RATE_LIMIT_MS)))
        .orElse(true);
  }
}

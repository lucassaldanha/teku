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

package tech.pegasys.teku.statetransition.util;

import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * Tracks, per target epoch, which validator indices have already had a valid attestation accepted
 * from them -- one bit per validator, keyed by epoch, rather than a capped set of (validator,
 * epoch) pairs. This mirrors Lighthouse's {@code ObservedAttesters}: every active validator always
 * gets its bit (no eviction pressure can drop a live entry), and epochs older than the retention
 * window are dropped wholesale instead of relying on LRU recency.
 */
public class SeenAttestingValidatorsCache {

  private final int maxCachedEpochs;
  private final Map<UInt64, BitSet> seenByEpoch = new ConcurrentHashMap<>();

  public SeenAttestingValidatorsCache(final int maxCachedEpochs) {
    this.maxCachedEpochs = maxCachedEpochs;
  }

  public boolean isAlreadySeen(final UInt64 epoch, final int validatorIndex) {
    final BitSet bitSet = seenByEpoch.get(epoch);
    if (bitSet == null) {
      return false;
    }
    synchronized (bitSet) {
      return bitSet.get(validatorIndex);
    }
  }

  /**
   * Records the validator as seen for this epoch. Returns false, without recording anything, if the
   * validator was already seen for this epoch.
   */
  public boolean addIfAbsent(final UInt64 epoch, final int validatorIndex) {
    pruneEpochsOlderThan(epoch);
    final BitSet bitSet = seenByEpoch.computeIfAbsent(epoch, __ -> new BitSet());
    synchronized (bitSet) {
      if (bitSet.get(validatorIndex)) {
        return false;
      }
      bitSet.set(validatorIndex);
      return true;
    }
  }

  private void pruneEpochsOlderThan(final UInt64 currentEpoch) {
    if (currentEpoch.isLessThan(maxCachedEpochs)) {
      return;
    }
    final UInt64 cutoff = currentEpoch.minus(maxCachedEpochs);
    seenByEpoch.keySet().removeIf(epoch -> epoch.isLessThanOrEqualTo(cutoff));
  }
}

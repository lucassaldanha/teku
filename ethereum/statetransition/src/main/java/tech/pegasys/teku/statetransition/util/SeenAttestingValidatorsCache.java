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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * Tracks, per target epoch, which validator indices have already had a valid attestation accepted
 * from them -- one bit per validator, keyed by epoch
 */
public class SeenAttestingValidatorsCache {

  private final int maxCachedEpochs;
  private final Map<UInt64, BitSet> seenByEpoch = new ConcurrentHashMap<>();

  // The highest target epoch seen by any call, tracked separately from each call's own epoch, so
  // pruning is monotonic: a validation for an old epoch that completes late (attestation
  // validation is async, so a duplicate check can finish well after a newer epoch has already
  // pruned it) can't resurrect an already-dropped BitSet or push the retention window backwards.
  private final AtomicReference<UInt64> highestEpoch = new AtomicReference<>(UInt64.ZERO);

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
   * validator was already seen for this epoch. If this epoch has already fallen out of the
   * retention window by the time this call runs, nothing is recorded and true is returned (nothing
   * to report as a duplicate); that's harmless since the propagation-slot-range checks elsewhere
   * already reject attestations that old before they ever reach this cache.
   */
  public boolean addIfAbsent(final UInt64 epoch, final int validatorIndex) {
    final Optional<UInt64> cutoff = pruneEpochsOlderThan(updateHighestEpoch(epoch));
    if (cutoff.map(epoch::isLessThanOrEqualTo).orElse(false)) {
      return true;
    }
    final BitSet bitSet = seenByEpoch.computeIfAbsent(epoch, __ -> new BitSet());
    synchronized (bitSet) {
      if (bitSet.get(validatorIndex)) {
        return false;
      }
      bitSet.set(validatorIndex);
      return true;
    }
  }

  private UInt64 updateHighestEpoch(final UInt64 epoch) {
    return highestEpoch.updateAndGet(current -> current.isGreaterThan(epoch) ? current : epoch);
  }

  /** Empty if fewer than maxCachedEpochs have elapsed yet, so nothing is old enough to prune. */
  private Optional<UInt64> pruneEpochsOlderThan(final UInt64 highestSeenEpoch) {
    if (highestSeenEpoch.isLessThan(maxCachedEpochs)) {
      return Optional.empty();
    }
    final UInt64 cutoff = highestSeenEpoch.minus(maxCachedEpochs);
    seenByEpoch.keySet().removeIf(epoch -> epoch.isLessThanOrEqualTo(cutoff));
    return Optional.of(cutoff);
  }
}

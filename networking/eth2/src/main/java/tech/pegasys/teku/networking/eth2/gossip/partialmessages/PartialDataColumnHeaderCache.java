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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.collections.LimitedMap;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;

/**
 * Thread-safe LRU cache of validated {@link PartialDataColumnHeaderFulu} objects, keyed on block
 * root.
 *
 * <p>A header validated on any data-column subnet is reusable across every subnet for the same
 * block (consensus-spec PR #4558). This cache holds the per-block-root validated header so that
 * {@code PartialDataColumnSidecarGossipValidator} and {@code PartialDataColumnSidecarHandler} can
 * retrieve it without re-validating.
 *
 * <p>The LRU capacity is supplied at construction time. Entries for finalized or sufficiently old
 * blocks are evicted automatically once the capacity is reached.
 */
public class PartialDataColumnHeaderCache {

  private final Map<Bytes32, PartialDataColumnHeaderFulu> cache;

  public static PartialDataColumnHeaderCache create(final int capacity) {
    return new PartialDataColumnHeaderCache(LimitedMap.createSynchronizedLRU(capacity));
  }

  /** Constructor for testing — wraps any map (e.g. a plain {@link HashMap}). */
  PartialDataColumnHeaderCache(final Map<Bytes32, PartialDataColumnHeaderFulu> backing) {
    this.cache = backing;
  }

  /**
   * Stores a validated header for the given block root. Existing entries for the same block root
   * are silently replaced (the validator already ensures they are equal before calling this).
   */
  public synchronized void put(final Bytes32 blockRoot, final PartialDataColumnHeaderFulu header) {
    cache.put(blockRoot, header);
  }

  /** Returns the cached header for {@code blockRoot}, or empty if not present. */
  public synchronized Optional<PartialDataColumnHeaderFulu> get(final Bytes32 blockRoot) {
    return Optional.ofNullable(cache.get(blockRoot));
  }

  /** Returns {@code true} if the cache contains a validated header for {@code blockRoot}. */
  public synchronized boolean contains(final Bytes32 blockRoot) {
    return cache.containsKey(blockRoot);
  }

  /**
   * Returns an unmodifiable snapshot view suitable for passing to the gossip validators. Changes to
   * the cache after this call are reflected in the returned map (it is a live view of the backing
   * map). Callers must synchronize externally if they iterate.
   */
  public Map<Bytes32, PartialDataColumnHeaderFulu> asMap() {
    return cache;
  }
}

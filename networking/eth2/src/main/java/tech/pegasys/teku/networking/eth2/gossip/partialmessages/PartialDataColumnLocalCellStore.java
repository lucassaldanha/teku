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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.collections.LimitedMap;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitlist;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.Cell;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;

/**
 * Thread-safe store for locally-known cells per {@code (blockRoot, columnIndex)} pair.
 *
 * <p>Cells accumulate as partial messages arrive from different peers. The store supports
 * idempotent merging: cells that are already present (according to the bitmap) are ignored, so
 * calling {@link #merge} multiple times with overlapping or disjoint bitmaps is safe.
 *
 * <p>LRU eviction is performed at the block-root level: once the capacity (number of distinct block
 * roots) is reached, the least-recently-used block root and all its column entries are evicted
 * together. Within a block root, all column indices are kept until the block root is evicted.
 */
public class PartialDataColumnLocalCellStore {

  /**
   * Per-column entry storing the cells we have accumulated so far.
   *
   * @param available bitmap of blob indices for which we have a cell (indexed by blob index within
   *     the column, i.e. row index)
   * @param cells ordered list of cells for the set bits in {@code available}
   * @param proofs ordered list of KZG proofs corresponding to {@code cells}
   */
  public record ColumnEntry(BitSet available, List<Cell> cells, List<SszKZGProof> proofs) {

    /** Creates an empty entry. */
    public static ColumnEntry empty() {
      return new ColumnEntry(new BitSet(), new ArrayList<>(), new ArrayList<>());
    }

    /** Returns how many cells we currently hold. */
    public int cellCount() {
      return available.cardinality();
    }
  }

  /** Composite key for the inner per-column map. */
  record ColumnKey(Bytes32 blockRoot, int columnIndex) {

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ColumnKey other)) {
        return false;
      }
      return columnIndex == other.columnIndex && Objects.equals(blockRoot, other.blockRoot);
    }

    @Override
    public int hashCode() {
      return Objects.hash(blockRoot, columnIndex);
    }
  }

  private final Map<Bytes32, Map<Integer, ColumnEntry>> store;

  /**
   * Creates a store whose LRU capacity is the given number of distinct block roots.
   *
   * @param blockRootCapacity maximum number of block roots to retain before eviction
   */
  public static PartialDataColumnLocalCellStore create(final int blockRootCapacity) {
    return new PartialDataColumnLocalCellStore(LimitedMap.createSynchronizedLRU(blockRootCapacity));
  }

  /** Constructor for testing — wraps any map. */
  PartialDataColumnLocalCellStore(final Map<Bytes32, Map<Integer, ColumnEntry>> backing) {
    this.store = backing;
  }

  /**
   * Merges the cells from {@code sidecar} into the store for {@code (blockRoot, columnIndex)}.
   *
   * <p>The sidecar's {@code cells_present_bitmap} determines which blob indices are present.
   * Existing cells (already recorded for the same blob index) are not overwritten.
   *
   * @return list of blob indices (within the column) that were novel — i.e. newly added by this
   *     call
   */
  public synchronized List<Integer> merge(
      final Bytes32 blockRoot, final int columnIndex, final PartialDataColumnSidecarFulu sidecar) {
    final Map<Integer, ColumnEntry> byColumn =
        store.computeIfAbsent(blockRoot, __ -> new HashMap<>());
    final ColumnEntry entry = byColumn.computeIfAbsent(columnIndex, __ -> ColumnEntry.empty());

    final SszBitlist bitmap = sidecar.getCellsPresentBitmap();
    final List<Integer> novelBlobIndices = new ArrayList<>();
    int sidecarCellIndex = 0;
    for (int blobIndex = 0; blobIndex < bitmap.size(); blobIndex++) {
      if (bitmap.getBit(blobIndex)) {
        if (!entry.available().get(blobIndex)) {
          // This cell is new; add it
          entry.available().set(blobIndex);
          entry.cells().add(sidecar.getPartialColumn().get(sidecarCellIndex));
          entry.proofs().add(sidecar.getKzgProofs().get(sidecarCellIndex));
          novelBlobIndices.add(blobIndex);
        }
        sidecarCellIndex++;
      }
    }
    return novelBlobIndices;
  }

  /**
   * Returns the column entry for {@code (blockRoot, columnIndex)}, or empty if nothing has been
   * stored yet.
   */
  public synchronized Optional<ColumnEntry> get(final Bytes32 blockRoot, final int columnIndex) {
    final Map<Integer, ColumnEntry> byColumn = store.get(blockRoot);
    if (byColumn == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byColumn.get(columnIndex));
  }

  /**
   * Returns {@code true} when all {@code totalExpected} cells for {@code (blockRoot, columnIndex)}
   * are present, i.e. reconstruction is possible.
   */
  public synchronized boolean isComplete(
      final Bytes32 blockRoot, final int columnIndex, final int totalExpected) {
    return get(blockRoot, columnIndex).map(e -> e.cellCount() == totalExpected).orElse(false);
  }

  /**
   * Returns {@code true} when all {@code totalExpected} cells are present for every column index in
   * the supplied list.
   */
  public synchronized boolean isCompleteForAll(
      final Bytes32 blockRoot, final List<Integer> columnIndices, final int totalExpected) {
    return columnIndices.stream().allMatch(col -> isComplete(blockRoot, col, totalExpected));
  }

  /** Returns the number of distinct block roots currently held in the store. */
  public synchronized int size() {
    return store.size();
  }

  /**
   * Convenience: builds a {@link BitSet} representing which blob indices we currently have for the
   * given {@code (blockRoot, columnIndex)}.
   */
  public synchronized BitSet getAvailableBitSet(final Bytes32 blockRoot, final int columnIndex) {
    return get(blockRoot, columnIndex)
        .map(e -> (BitSet) e.available().clone())
        .orElseGet(BitSet::new);
  }
}

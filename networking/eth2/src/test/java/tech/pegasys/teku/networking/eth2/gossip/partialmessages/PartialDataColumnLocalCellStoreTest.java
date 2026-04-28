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

import java.util.List;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarSchemaFulu;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsFulu;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class PartialDataColumnLocalCellStoreTest {

  private final Spec spec = TestSpecFactory.createMinimalFulu();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final SchemaDefinitionsFulu schemas =
      SchemaDefinitionsFulu.required(spec.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());
  private final PartialDataColumnSidecarSchemaFulu sidecarSchema =
      schemas.getPartialDataColumnSidecarSchema();

  @Test
  void getReturnsEmpty_whenNothingStored() {
    final PartialDataColumnLocalCellStore store = PartialDataColumnLocalCellStore.create(10);
    assertThat(store.get(dataStructureUtil.randomBytes32(), 0)).isEmpty();
  }

  @Test
  void merge_disjointBitmaps_addsAllCells() {
    final PartialDataColumnLocalCellStore store = PartialDataColumnLocalCellStore.create(10);
    final Bytes32 blockRoot = dataStructureUtil.randomBytes32();
    final int columnIndex = 0;

    // First partial: bit 0
    final var sidecar0 = buildSidecar(new int[] {}, new int[] {0});
    final List<Integer> novel0 = store.merge(blockRoot, columnIndex, sidecar0);
    assertThat(novel0).containsExactly(0);

    // Second partial: bit 2
    final var sidecar2 = buildSidecar(new int[] {0}, new int[] {2});
    final List<Integer> novel2 = store.merge(blockRoot, columnIndex, sidecar2);
    assertThat(novel2).containsExactly(2);

    final var entry = store.get(blockRoot, columnIndex).orElseThrow();
    assertThat(entry.cellCount()).isEqualTo(2);
    assertThat(entry.available().get(0)).isTrue();
    assertThat(entry.available().get(2)).isTrue();
  }

  @Test
  void merge_overlappingBitmaps_isIdempotent() {
    final PartialDataColumnLocalCellStore store = PartialDataColumnLocalCellStore.create(10);
    final Bytes32 blockRoot = dataStructureUtil.randomBytes32();
    final int columnIndex = 0;

    // First merge: bits 0 and 1
    final var sidecar01 = buildSidecar(new int[] {}, new int[] {0, 1});
    store.merge(blockRoot, columnIndex, sidecar01);

    // Second merge: same bits (duplicate) — should return no novel cells
    final var sidecar01again = buildSidecar(new int[] {}, new int[] {0, 1});
    final List<Integer> novel = store.merge(blockRoot, columnIndex, sidecar01again);

    assertThat(novel).isEmpty();
    assertThat(store.get(blockRoot, columnIndex).orElseThrow().cellCount()).isEqualTo(2);
  }

  @Test
  void merge_partiallyOverlapping_returnsOnlyNovelIndices() {
    final PartialDataColumnLocalCellStore store = PartialDataColumnLocalCellStore.create(10);
    final Bytes32 blockRoot = dataStructureUtil.randomBytes32();
    final int columnIndex = 0;

    store.merge(blockRoot, columnIndex, buildSidecar(new int[] {}, new int[] {0}));
    // Overlap on 0, novel on 1
    final List<Integer> novel =
        store.merge(blockRoot, columnIndex, buildSidecar(new int[] {}, new int[] {0, 1}));
    assertThat(novel).containsExactly(1);
  }

  @Test
  void isComplete_returnsFalse_whenNotEnoughCells() {
    final PartialDataColumnLocalCellStore store = PartialDataColumnLocalCellStore.create(10);
    final Bytes32 blockRoot = dataStructureUtil.randomBytes32();
    store.merge(blockRoot, 0, buildSidecar(new int[] {}, new int[] {0}));
    assertThat(store.isComplete(blockRoot, 0, 3)).isFalse();
  }

  @Test
  void isComplete_returnsTrue_whenAllCellsPresent() {
    final PartialDataColumnLocalCellStore store = PartialDataColumnLocalCellStore.create(10);
    final Bytes32 blockRoot = dataStructureUtil.randomBytes32();
    store.merge(blockRoot, 0, buildSidecar(new int[] {}, new int[] {0, 1, 2}));
    assertThat(store.isComplete(blockRoot, 0, 3)).isTrue();
  }

  @Test
  void lruEviction_evictsOldestBlockRoot_whenCapacityExceeded() {
    final int capacity = 2;
    final PartialDataColumnLocalCellStore store = PartialDataColumnLocalCellStore.create(capacity);

    final Bytes32 first = dataStructureUtil.randomBytes32();
    final Bytes32 second = dataStructureUtil.randomBytes32();
    final Bytes32 third = dataStructureUtil.randomBytes32();

    store.merge(first, 0, buildSidecar(new int[] {}, new int[] {0}));
    store.merge(second, 0, buildSidecar(new int[] {}, new int[] {0}));
    assertThat(store.size()).isEqualTo(2);

    // Adding third should evict first (LRU)
    store.merge(third, 0, buildSidecar(new int[] {}, new int[] {0}));
    assertThat(store.size()).isEqualTo(2);
    assertThat(store.get(first, 0)).isEmpty();
    assertThat(store.get(second, 0)).isPresent();
    assertThat(store.get(third, 0)).isPresent();
  }

  @Test
  void getAvailableBitSet_returnsEmptyBitSet_whenNotPresent() {
    final PartialDataColumnLocalCellStore store = PartialDataColumnLocalCellStore.create(10);
    assertThat(store.getAvailableBitSet(dataStructureUtil.randomBytes32(), 0).isEmpty()).isTrue();
  }

  /**
   * Builds a minimal PartialDataColumnSidecarFulu where {@code alreadySet} bit indices are pre-set
   * (simulating existing cells from a prior merge) and {@code newBits} bit indices are newly
   * present in this message.
   *
   * <p>The combined set of bits (alreadySet ∪ newBits) is encoded in {@code cells_present_bitmap},
   * and one random Cell and proof is added for each bit in {@code newBits} (appended after any
   * cells for alreadySet bits).
   */
  private PartialDataColumnSidecarFulu buildSidecar(
      final int[] alreadySetBits, final int[] newBits) {
    // Determine total bits to set
    final int maxBit =
        Math.max(
            alreadySetBits.length == 0 ? -1 : alreadySetBits[alreadySetBits.length - 1],
            newBits.length == 0 ? -1 : newBits[newBits.length - 1]);
    final int bitmapSize = maxBit + 1;
    final int[] allBits = concatenate(alreadySetBits, newBits);
    final var bitmap = sidecarSchema.getCellsPresentBitmapSchema().ofBits(bitmapSize, allBits);

    // One cell/proof per set bit in the combined bitmap
    final var cells =
        sidecarSchema
            .getPartialColumnSchema()
            .createFromElements(
                IntStream.range(0, allBits.length)
                    .mapToObj(__ -> dataStructureUtil.randomCell())
                    .toList());
    final var proofs =
        sidecarSchema
            .getKzgProofsSchema()
            .createFromElements(
                IntStream.range(0, allBits.length)
                    .mapToObj(__ -> new SszKZGProof(dataStructureUtil.randomKZGProof()))
                    .toList());

    return sidecarSchema.create(
        bitmap, cells, proofs, sidecarSchema.getHeaderSchema().createFromElements(List.of()));
  }

  private static int[] concatenate(final int[] a, final int[] b) {
    final int[] result = new int[a.length + b.length];
    System.arraycopy(a, 0, result, 0, a.length);
    System.arraycopy(b, 0, result, a.length, b.length);
    return result;
  }
}

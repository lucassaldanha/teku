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

import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.DataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderSchemaFulu;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsFulu;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class PartialDataColumnHeaderCacheTest {

  private final Spec spec = TestSpecFactory.createMinimalFulu();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final SchemaDefinitionsFulu schemas =
      SchemaDefinitionsFulu.required(spec.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());

  @Test
  void getReturnsEmpty_whenNothingCached() {
    final PartialDataColumnHeaderCache cache = PartialDataColumnHeaderCache.create(10);
    assertThat(cache.get(dataStructureUtil.randomBytes32())).isEmpty();
  }

  @Test
  void getReturnsHeader_afterPut() {
    final PartialDataColumnHeaderCache cache = PartialDataColumnHeaderCache.create(10);
    final Bytes32 blockRoot = dataStructureUtil.randomBytes32();
    final PartialDataColumnHeaderFulu header = randomHeader();

    cache.put(blockRoot, header);

    assertThat(cache.get(blockRoot)).isPresent().contains(header);
    assertThat(cache.contains(blockRoot)).isTrue();
  }

  @Test
  void lruEviction_removesOldestEntry_whenCapacityExceeded() {
    final int capacity = 3;
    final PartialDataColumnHeaderCache cache = PartialDataColumnHeaderCache.create(capacity);

    final Bytes32 firstRoot = dataStructureUtil.randomBytes32();
    cache.put(firstRoot, randomHeader());

    // Fill to capacity
    IntStream.range(0, capacity - 1)
        .forEach(__ -> cache.put(dataStructureUtil.randomBytes32(), randomHeader()));

    // First entry is still present (capacity not yet exceeded)
    assertThat(cache.contains(firstRoot)).isTrue();

    // Adding one more entry should evict the first (LRU)
    cache.put(dataStructureUtil.randomBytes32(), randomHeader());

    assertThat(cache.contains(firstRoot)).isFalse();
  }

  @Test
  void asMap_reflectsPuttedEntries() {
    final PartialDataColumnHeaderCache cache = PartialDataColumnHeaderCache.create(10);
    final Bytes32 blockRoot = dataStructureUtil.randomBytes32();
    final PartialDataColumnHeaderFulu header = randomHeader();

    cache.put(blockRoot, header);

    assertThat(cache.asMap()).containsEntry(blockRoot, header);
  }

  private PartialDataColumnHeaderFulu randomHeader() {
    final DataColumnSidecar realSidecar =
        dataStructureUtil.randomDataColumnSidecar(
            dataStructureUtil.randomSignedBeaconBlock(2L, dataStructureUtil.randomBytes32()),
            UInt64.ZERO);
    final DataColumnSidecarFulu realFulu = DataColumnSidecarFulu.required(realSidecar);
    final PartialDataColumnHeaderSchemaFulu headerSchema =
        schemas.getPartialDataColumnHeaderSchema();
    return headerSchema.create(
        realFulu.getKzgCommitments(),
        realFulu.getSignedBlockHeader(),
        realFulu.getKzgCommitmentsInclusionProof());
  }
}

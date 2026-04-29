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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.gossip.partialmessages.jvmlibp2p.PartialGossip;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.DataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderSchemaFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnPartsMetadata.PartialDataColumnPartsMetadataSchema;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarSchemaFulu;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsFulu;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class PartialDataColumnSidecarPublisherTest {

  private static final String TOPIC = "/eth2/aabbccdd/data_column_sidecar_0/ssz_snappy";
  private static final int COLUMN_INDEX = 0;

  private final Spec spec = TestSpecFactory.createMinimalFulu();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final SchemaDefinitionsFulu schemas =
      SchemaDefinitionsFulu.required(spec.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());
  private final PartialDataColumnSidecarSchemaFulu sidecarSchema =
      schemas.getPartialDataColumnSidecarSchema();
  private final PartialDataColumnPartsMetadataSchema metadataSchema =
      schemas.getPartialDataColumnPartsMetadataSchema();

  private final PartialGossip partialGossip = mock(PartialGossip.class);
  private final PartialDataColumnHeaderCache headerCache = PartialDataColumnHeaderCache.create(10);
  private final PartialDataColumnLocalCellStore cellStore =
      PartialDataColumnLocalCellStore.create(10);

  private PartialDataColumnSidecarPublisher publisher;
  private Bytes32 blockRoot;

  @BeforeEach
  void setup() {
    publisher =
        new PartialDataColumnSidecarPublisher(
            partialGossip, headerCache, cellStore, sidecarSchema, metadataSchema);

    when(partialGossip.publishPartial(any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));

    final SignedBeaconBlock block =
        dataStructureUtil.randomSignedBeaconBlock(2L, dataStructureUtil.randomBytes32());
    final DataColumnSidecar realSidecar =
        dataStructureUtil.randomDataColumnSidecar(block, UInt64.ZERO);
    final DataColumnSidecarFulu realFulu = DataColumnSidecarFulu.required(realSidecar);

    final PartialDataColumnHeaderSchemaFulu headerSchema =
        schemas.getPartialDataColumnHeaderSchema();
    final PartialDataColumnHeaderFulu header =
        headerSchema.create(
            realFulu.getKzgCommitments(),
            realFulu.getSignedBlockHeader(),
            realFulu.getKzgCommitmentsInclusionProof());

    blockRoot = realFulu.getSignedBlockHeader().getMessage().hashTreeRoot();
    headerCache.put(blockRoot, header);
  }

  @Test
  void publishReactive_callsGossipPublishPartialWithCorrectGroupId() {
    final var result = publisher.publishReactive(TOPIC, blockRoot, COLUMN_INDEX, Map.of());

    assertThat(result).isCompleted();
    verify(partialGossip).publishPartial(eq(TOPIC), any(byte[].class), any());
  }

  @Test
  void publishReactive_groupIdHasCorrectFormat() {
    final var capturedGroupIds = new ArrayList<byte[]>();
    when(partialGossip.publishPartial(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              capturedGroupIds.add(invocation.getArgument(1));
              return CompletableFuture.completedFuture(null);
            });

    final var ignored = publisher.publishReactive(TOPIC, blockRoot, COLUMN_INDEX, Map.of());

    assertThat(capturedGroupIds).hasSize(1);
    final byte[] groupId = capturedGroupIds.get(0);
    assertThat(groupId).hasSize(33);
    assertThat(groupId[0]).isZero(); // version byte 0x00
    assertThat(Bytes32.wrap(groupId, 1)).isEqualTo(blockRoot);
  }

  @Test
  void publishMetadataOnly_callsGossipPublishPartial() {
    final var result = publisher.publishMetadataOnly(TOPIC, blockRoot, COLUMN_INDEX);

    assertThat(result).isCompleted();
    verify(partialGossip).publishPartial(eq(TOPIC), any(byte[].class), any());
  }

  @Test
  void publishReactive_withNoCellsInStore_returnsEmptyActionsForPeer() {
    // No cells in store; publishReactive should still succeed (empty actions)
    final var result = publisher.publishReactive(TOPIC, blockRoot, COLUMN_INDEX, Map.of());
    assertThat(result).isCompleted();
  }

  @Test
  void publishReactive_withCellsInStore_triggersPublish() {
    // Add a cell to the store
    final var dummySidecar =
        sidecarSchema.create(
            sidecarSchema.getCellsPresentBitmapSchema().ofBits(1, 0),
            sidecarSchema
                .getPartialColumnSchema()
                .createFromElements(List.of(dataStructureUtil.randomCell())),
            sidecarSchema
                .getKzgProofsSchema()
                .createFromElements(List.of(new SszKZGProof(dataStructureUtil.randomKZGProof()))),
            sidecarSchema.getHeaderSchema().createFromElements(List.of()));

    cellStore.merge(blockRoot, COLUMN_INDEX, dummySidecar);
    assertThat(cellStore.get(blockRoot, COLUMN_INDEX)).isPresent();

    final var result = publisher.publishReactive(TOPIC, blockRoot, COLUMN_INDEX, Map.of());
    assertThat(result).isCompleted();
    verify(partialGossip).publishPartial(eq(TOPIC), any(byte[].class), any());
  }
}

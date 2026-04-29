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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.p2p.gossip.GossipNetwork;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.DataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderSchemaFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarSchemaFulu;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsFulu;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.statetransition.datacolumns.DataColumnSidecarManager;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;

class PartialDataColumnReassemblerTest {

  private final Spec spec = TestSpecFactory.createMinimalFulu();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final SchemaDefinitionsFulu schemas =
      SchemaDefinitionsFulu.required(spec.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());

  private final PartialDataColumnHeaderCache headerCache = PartialDataColumnHeaderCache.create(10);
  private final PartialDataColumnLocalCellStore cellStore =
      PartialDataColumnLocalCellStore.create(10);
  private final DataColumnSidecarManager manager = mock(DataColumnSidecarManager.class);
  private final GossipNetwork gossipNetwork = mock(GossipNetwork.class);

  private PartialDataColumnReassembler reassembler;
  private Bytes32 blockRoot;
  private PartialDataColumnHeaderFulu header;
  private int columnIndex;

  @BeforeEach
  void setup() {
    reassembler = new PartialDataColumnReassembler(spec, headerCache, manager, gossipNetwork);

    columnIndex = 0;

    final SignedBeaconBlock block =
        dataStructureUtil.randomSignedBeaconBlock(2L, dataStructureUtil.randomBytes32());
    final DataColumnSidecar realSidecar =
        dataStructureUtil.randomDataColumnSidecar(block, UInt64.valueOf(columnIndex));
    final DataColumnSidecarFulu realFulu = DataColumnSidecarFulu.required(realSidecar);

    final PartialDataColumnHeaderSchemaFulu headerSchema =
        schemas.getPartialDataColumnHeaderSchema();
    header =
        headerSchema.create(
            realFulu.getKzgCommitments(),
            realFulu.getSignedBlockHeader(),
            realFulu.getKzgCommitmentsInclusionProof());

    blockRoot = realFulu.getSignedBlockHeader().getMessage().hashTreeRoot();
    headerCache.put(blockRoot, header);

    when(manager.onDataColumnSidecarGossip(any(), any()))
        .thenReturn(SafeFuture.completedFuture(InternalValidationResult.ACCEPT));
  }

  @Test
  void onReconstructionComplete_noHeaderCached_doesNotCallManager() {
    final Bytes32 unknownBlockRoot = dataStructureUtil.randomBytes32();
    final var entry = PartialDataColumnLocalCellStore.ColumnEntry.empty();

    reassembler.onReconstructionComplete(unknownBlockRoot, columnIndex, entry);

    verify(manager, never()).onDataColumnSidecarGossip(any(), any());
  }

  @Test
  void onReconstructionComplete_withValidData_callsManager() {
    // Build partial sidecar and merge into cell store
    final PartialDataColumnSidecarSchemaFulu sidecarSchema =
        schemas.getPartialDataColumnSidecarSchema();
    final int numCells = header.getKzgCommitments().size();
    final int[] allBits = IntStream.range(0, numCells).toArray();
    final var dummySidecar =
        sidecarSchema.create(
            sidecarSchema.getCellsPresentBitmapSchema().ofBits(numCells, allBits),
            sidecarSchema
                .getPartialColumnSchema()
                .createFromElements(
                    IntStream.range(0, numCells)
                        .mapToObj(__ -> dataStructureUtil.randomCell())
                        .toList()),
            sidecarSchema
                .getKzgProofsSchema()
                .createFromElements(
                    IntStream.range(0, numCells)
                        .mapToObj(__ -> new SszKZGProof(dataStructureUtil.randomKZGProof()))
                        .toList()),
            sidecarSchema.getHeaderSchema().createFromElements(List.of()));

    cellStore.merge(blockRoot, columnIndex, dummySidecar);
    final var entry = cellStore.get(blockRoot, columnIndex).orElseThrow();

    final AtomicReference<DataColumnSidecar> capturedSidecar = new AtomicReference<>();
    when(manager.onDataColumnSidecarGossip(any(), any()))
        .thenAnswer(
            inv -> {
              capturedSidecar.set(inv.getArgument(0));
              return SafeFuture.completedFuture(InternalValidationResult.ACCEPT);
            });

    reassembler.onReconstructionComplete(blockRoot, columnIndex, entry);

    verify(manager).onDataColumnSidecarGossip(any(), any());
    assertThat(capturedSidecar.get()).isNotNull();
    assertThat(capturedSidecar.get().getIndex()).isEqualTo(UInt64.valueOf(columnIndex));
    assertThat(capturedSidecar.get().getColumn().size()).isEqualTo(numCells);
  }
}

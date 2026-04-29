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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.libp2p.core.PeerId;
import io.libp2p.pubsub.gossip.partialmessages.FeedbackKind;
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesPeerFeedback;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pubsub.pb.Rpc;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.async.StubAsyncRunner;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.DataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderSchemaFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnPartsMetadata;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnPartsMetadata.PartialDataColumnPartsMetadataSchema;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarSchemaFulu;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsFulu;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;
import tech.pegasys.teku.statetransition.validation.PartialDataColumnHeaderGossipValidator;
import tech.pegasys.teku.statetransition.validation.PartialDataColumnSidecarGossipValidator;

class PartialDataColumnSidecarHandlerTest {

  private static final String TOPIC_ID = "/eth2/aabbccdd/data_column_sidecar_0/ssz_snappy";
  private static final int COLUMN_INDEX = 0;

  private final Spec spec = TestSpecFactory.createMinimalFulu();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final SchemaDefinitionsFulu schemas =
      SchemaDefinitionsFulu.required(spec.forMilestone(SpecMilestone.FULU).getSchemaDefinitions());
  private final PartialDataColumnSidecarSchemaFulu sidecarSchema =
      schemas.getPartialDataColumnSidecarSchema();
  private final PartialDataColumnPartsMetadataSchema metadataSchema =
      schemas.getPartialDataColumnPartsMetadataSchema();

  private final StubAsyncRunner asyncRunner = new StubAsyncRunner();
  private final PartialDataColumnHeaderGossipValidator headerValidator =
      mock(PartialDataColumnHeaderGossipValidator.class);
  private final PartialDataColumnSidecarGossipValidator cellValidator =
      mock(PartialDataColumnSidecarGossipValidator.class);
  private final PartialDataColumnLocalCellStore cellStore =
      PartialDataColumnLocalCellStore.create(10);
  private final PartialMessagesPeerFeedback feedback = mock(PartialMessagesPeerFeedback.class);
  private final AtomicInteger reconstructionCount = new AtomicInteger(0);

  private PartialDataColumnSidecarHandler handler;
  private PeerId peerId;
  private Bytes32 blockRoot;
  private PartialDataColumnHeaderFulu validHeader;

  @BeforeEach
  void setup() {
    handler =
        new PartialDataColumnSidecarHandler(
            asyncRunner,
            headerValidator,
            cellValidator,
            cellStore,
            metadataSchema,
            sidecarSchema,
            (root, col, entry) -> reconstructionCount.incrementAndGet());

    peerId = new PeerId(dataStructureUtil.randomBytes(39).toArrayUnsafe());

    final SignedBeaconBlock block =
        dataStructureUtil.randomSignedBeaconBlock(2L, dataStructureUtil.randomBytes32());
    final DataColumnSidecar realSidecar =
        dataStructureUtil.randomDataColumnSidecar(block, UInt64.ZERO);
    final DataColumnSidecarFulu realFulu = DataColumnSidecarFulu.required(realSidecar);

    final PartialDataColumnHeaderSchemaFulu headerSchema =
        schemas.getPartialDataColumnHeaderSchema();
    validHeader =
        headerSchema.create(
            realFulu.getKzgCommitments(),
            realFulu.getSignedBlockHeader(),
            realFulu.getKzgCommitmentsInclusionProof());

    blockRoot = realFulu.getSignedBlockHeader().getMessage().hashTreeRoot();

    // Default validator mocks
    when(headerValidator.validate(any(), any()))
        .thenReturn(SafeFuture.completedFuture(InternalValidationResult.ACCEPT));
    when(cellValidator.validate(any(), any(), eq(COLUMN_INDEX)))
        .thenReturn(SafeFuture.completedFuture(InternalValidationResult.ACCEPT));
  }

  // ---- Combo 1: no header, no cells ------------------------------------------

  @Test
  void noHeaderNoCells_doesNotDispatchWork() {
    final Rpc.PartialMessagesExtension rpc = buildRpc(blockRoot, null, null);

    handler.onIncomingRpc(peerId, Map.of(), rpc, feedback);
    asyncRunner.executeQueuedActions();

    verify(headerValidator, never()).validate(any(), any());
    verify(cellValidator, never()).validate(any(), any(), eq(COLUMN_INDEX));
    verify(feedback, never()).reportFeedback(any(), any(), any());
  }

  // ---- Combo 2: header present, no cells -------------------------------------

  @Test
  void headerOnly_accept_cachesFeedback() {
    final Rpc.PartialMessagesExtension rpc =
        buildRpc(blockRoot, buildSidecarWithHeader(validHeader), null);

    handler.onIncomingRpc(peerId, Map.of(), rpc, feedback);
    asyncRunner.executeQueuedActions();

    verify(headerValidator, atLeastOnce()).validate(any(), eq(blockRoot));
    verify(cellValidator, never()).validate(any(), any(), eq(COLUMN_INDEX));
  }

  @Test
  void headerOnly_reject_reportsFeedbackInvalid() {
    when(headerValidator.validate(any(), any()))
        .thenReturn(SafeFuture.completedFuture(InternalValidationResult.reject("bad signature")));

    final Rpc.PartialMessagesExtension rpc =
        buildRpc(blockRoot, buildSidecarWithHeader(validHeader), null);

    handler.onIncomingRpc(peerId, Map.of(), rpc, feedback);
    asyncRunner.executeQueuedActions();

    verify(feedback).reportFeedback(eq(TOPIC_ID), eq(peerId), eq(FeedbackKind.INVALID));
  }

  @Test
  void headerOnly_ignore_reportsFeedbackIgnored() {
    when(headerValidator.validate(any(), any()))
        .thenReturn(SafeFuture.completedFuture(InternalValidationResult.ignore("future slot")));

    final Rpc.PartialMessagesExtension rpc =
        buildRpc(blockRoot, buildSidecarWithHeader(validHeader), null);

    handler.onIncomingRpc(peerId, Map.of(), rpc, feedback);
    asyncRunner.executeQueuedActions();

    verify(feedback).reportFeedback(eq(TOPIC_ID), eq(peerId), eq(FeedbackKind.IGNORED));
  }

  // ---- Combo 3: cells present, no header in message --------------------------

  @Test
  void cellsOnly_noHeaderCached_reportsFeedbackIgnored() {
    when(cellValidator.validate(any(), any(), eq(COLUMN_INDEX)))
        .thenReturn(SafeFuture.completedFuture(InternalValidationResult.ignore("no header")));

    final Rpc.PartialMessagesExtension rpc =
        buildRpc(blockRoot, buildSidecarWithCells(new int[] {0}), null);

    handler.onIncomingRpc(peerId, Map.of(), rpc, feedback);
    asyncRunner.executeQueuedActions();

    verify(headerValidator, never()).validate(any(), any());
    verify(feedback).reportFeedback(eq(TOPIC_ID), eq(peerId), eq(FeedbackKind.IGNORED));
  }

  @Test
  void cellsOnly_valid_mergesIntoStoreAndReportsUseful() {
    final Rpc.PartialMessagesExtension rpc =
        buildRpc(blockRoot, buildSidecarWithCells(new int[] {0}), null);

    handler.onIncomingRpc(peerId, Map.of(), rpc, feedback);
    asyncRunner.executeQueuedActions();

    assertThat(cellStore.get(blockRoot, COLUMN_INDEX)).isPresent();
    verify(feedback).reportFeedback(eq(TOPIC_ID), eq(peerId), eq(FeedbackKind.USEFUL));
  }

  @Test
  void cellsOnly_reject_reportsFeedbackInvalid() {
    when(cellValidator.validate(any(), any(), eq(COLUMN_INDEX)))
        .thenReturn(SafeFuture.completedFuture(InternalValidationResult.reject("bad kzg")));

    final Rpc.PartialMessagesExtension rpc =
        buildRpc(blockRoot, buildSidecarWithCells(new int[] {0}), null);

    handler.onIncomingRpc(peerId, Map.of(), rpc, feedback);
    asyncRunner.executeQueuedActions();

    verify(feedback).reportFeedback(eq(TOPIC_ID), eq(peerId), eq(FeedbackKind.INVALID));
  }

  // ---- Combo 4: header + cells ------------------------------------------------

  @Test
  void headerAndCells_bothValid_mergesAndReportsUseful() {
    final Rpc.PartialMessagesExtension rpc =
        buildRpc(blockRoot, buildSidecarWithBoth(validHeader, new int[] {0}), null);

    handler.onIncomingRpc(peerId, Map.of(), rpc, feedback);
    asyncRunner.executeQueuedActions();

    verify(headerValidator, atLeastOnce()).validate(any(), eq(blockRoot));
    verify(feedback).reportFeedback(eq(TOPIC_ID), eq(peerId), eq(FeedbackKind.USEFUL));
    assertThat(cellStore.get(blockRoot, COLUMN_INDEX)).isPresent();
  }

  @Test
  void headerAndCells_headerRejected_doesNotValidateCells() {
    when(headerValidator.validate(any(), any()))
        .thenReturn(SafeFuture.completedFuture(InternalValidationResult.reject("bad")));

    final Rpc.PartialMessagesExtension rpc =
        buildRpc(blockRoot, buildSidecarWithBoth(validHeader, new int[] {0}), null);

    handler.onIncomingRpc(peerId, Map.of(), rpc, feedback);
    asyncRunner.executeQueuedActions();

    verify(cellValidator, never()).validate(any(), any(), eq(COLUMN_INDEX));
    verify(feedback).reportFeedback(eq(TOPIC_ID), eq(peerId), eq(FeedbackKind.INVALID));
  }

  // ---- Misc: invalid groupId -------------------------------------------------

  @Test
  void invalidGroupIdVersionByte_dropsMessage() {
    // Group ID with wrong version byte
    final byte[] badGroupId = new byte[33];
    badGroupId[0] = 0x01; // wrong version
    final Rpc.PartialMessagesExtension rpc =
        Rpc.PartialMessagesExtension.newBuilder()
            .setTopicID(TOPIC_ID)
            .setGroupID(ByteString.copyFrom(badGroupId))
            .build();

    handler.onIncomingRpc(peerId, Map.of(), rpc, feedback);
    asyncRunner.executeQueuedActions();

    verify(headerValidator, never()).validate(any(), any());
    verify(feedback, never()).reportFeedback(any(), any(), any());
  }

  @Test
  void invalidTopic_dropsMessage() {
    final byte[] groupIdBytes = buildGroupIdBytes(blockRoot);
    final Rpc.PartialMessagesExtension rpc =
        Rpc.PartialMessagesExtension.newBuilder()
            .setTopicID("/eth2/aabbccdd/blob_sidecar_0/ssz_snappy") // not data_column_sidecar
            .setGroupID(ByteString.copyFrom(groupIdBytes))
            .build();

    handler.onIncomingRpc(peerId, Map.of(), rpc, feedback);
    asyncRunner.executeQueuedActions();

    verify(headerValidator, never()).validate(any(), any());
    verify(feedback, never()).reportFeedback(any(), any(), any());
  }

  // ---- Duplicate cells are ignored -------------------------------------------

  @Test
  void duplicateCells_reportIgnored() {
    final Rpc.PartialMessagesExtension rpc =
        buildRpc(blockRoot, buildSidecarWithCells(new int[] {0}), null);

    // First delivery → USEFUL
    handler.onIncomingRpc(peerId, Map.of(), rpc, feedback);
    asyncRunner.executeQueuedActions();

    // Second delivery → IGNORED (already in cell store)
    handler.onIncomingRpc(peerId, Map.of(), rpc, feedback);
    asyncRunner.executeQueuedActions();

    verify(feedback, atLeastOnce())
        .reportFeedback(eq(TOPIC_ID), eq(peerId), eq(FeedbackKind.IGNORED));
  }

  // ---- Helpers ---------------------------------------------------------------

  private Rpc.PartialMessagesExtension buildRpc(
      final Bytes32 blockRoot,
      final PartialDataColumnSidecarFulu sidecar,
      final PartialDataColumnPartsMetadata metadata) {
    final Rpc.PartialMessagesExtension.Builder builder =
        Rpc.PartialMessagesExtension.newBuilder()
            .setTopicID(TOPIC_ID)
            .setGroupID(ByteString.copyFrom(buildGroupIdBytes(blockRoot)));

    if (sidecar != null) {
      builder.setPartialMessage(ByteString.copyFrom(sidecar.sszSerialize().toArrayUnsafe()));
    }
    if (metadata != null) {
      builder.setPartsMetadata(ByteString.copyFrom(metadata.sszSerialize().toArrayUnsafe()));
    }
    return builder.build();
  }

  private static byte[] buildGroupIdBytes(final Bytes32 blockRoot) {
    final byte[] groupIdBytes = new byte[33];
    groupIdBytes[0] = 0x00;
    System.arraycopy(blockRoot.toArrayUnsafe(), 0, groupIdBytes, 1, 32);
    return groupIdBytes;
  }

  private PartialDataColumnSidecarFulu buildSidecarWithHeader(
      final PartialDataColumnHeaderFulu header) {
    return sidecarSchema.create(
        sidecarSchema.getCellsPresentBitmapSchema().ofBits(0),
        sidecarSchema.getPartialColumnSchema().createFromElements(List.of()),
        sidecarSchema.getKzgProofsSchema().createFromElements(List.of()),
        sidecarSchema.getHeaderSchema().createFromElements(List.of(header)));
  }

  private PartialDataColumnSidecarFulu buildSidecarWithCells(final int[] setBits) {
    final int maxBit = setBits.length == 0 ? 0 : setBits[setBits.length - 1];
    final var bitmap = sidecarSchema.getCellsPresentBitmapSchema().ofBits(maxBit + 1, setBits);
    final var cells =
        sidecarSchema
            .getPartialColumnSchema()
            .createFromElements(
                IntStream.range(0, setBits.length)
                    .mapToObj(__ -> dataStructureUtil.randomCell())
                    .toList());
    final var proofs =
        sidecarSchema
            .getKzgProofsSchema()
            .createFromElements(
                IntStream.range(0, setBits.length)
                    .mapToObj(__ -> new SszKZGProof(dataStructureUtil.randomKZGProof()))
                    .toList());
    return sidecarSchema.create(
        bitmap, cells, proofs, sidecarSchema.getHeaderSchema().createFromElements(List.of()));
  }

  private PartialDataColumnSidecarFulu buildSidecarWithBoth(
      final PartialDataColumnHeaderFulu header, final int[] setBits) {
    final int maxBit = setBits.length == 0 ? 0 : setBits[setBits.length - 1];
    final var bitmap = sidecarSchema.getCellsPresentBitmapSchema().ofBits(maxBit + 1, setBits);
    final var cells =
        sidecarSchema
            .getPartialColumnSchema()
            .createFromElements(
                IntStream.range(0, setBits.length)
                    .mapToObj(__ -> dataStructureUtil.randomCell())
                    .toList());
    final var proofs =
        sidecarSchema
            .getKzgProofsSchema()
            .createFromElements(
                IntStream.range(0, setBits.length)
                    .mapToObj(__ -> new SszKZGProof(dataStructureUtil.randomKZGProof()))
                    .toList());
    return sidecarSchema.create(
        bitmap, cells, proofs, sidecarSchema.getHeaderSchema().createFromElements(List.of(header)));
  }
}

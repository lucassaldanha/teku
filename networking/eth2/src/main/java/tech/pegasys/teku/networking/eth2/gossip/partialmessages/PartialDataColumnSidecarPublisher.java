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

import io.libp2p.core.PeerId;
import io.libp2p.pubsub.gossip.Gossip;
import io.libp2p.pubsub.gossip.partialmessages.PublishAction;
import io.libp2p.pubsub.gossip.partialmessages.PublishActionsFn;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kotlin.Pair;
import kotlin.jvm.functions.Function1;
import kotlin.sequences.SequencesKt;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.Cell;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnPartsMetadata;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnPartsMetadata.PartialDataColumnPartsMetadataSchema;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarSchemaFulu;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;

/**
 * Drives outbound partial-messages RPCs for {@code data_column_sidecar_{subnet_id}} topics.
 *
 * <p>Implements two of the four publishing call sites from §7.1 of the design doc:
 *
 * <ol>
 *   <li><b>Reactive publish</b> (call site 1): triggered by {@link PartialDataColumnSidecarHandler}
 *       when new cells arrive that a peer has requested.
 *   <li><b>Metadata-only heartbeat</b> (call site 2): triggered from {@link
 *       PartialDataColumnSidecarHandler#onEmitGossip} to refresh peers' availability views.
 * </ol>
 *
 * <p>Both publish paths are implemented through {@link Gossip#publishPartial}, which routes the RPC
 * to all partial-capable mesh peers via the jvm-libp2p router.
 */
public class PartialDataColumnSidecarPublisher {

  private static final byte GROUP_ID_VERSION_BYTE = 0x00;

  private final Gossip gossip;
  private final PartialDataColumnHeaderCache headerCache;
  private final PartialDataColumnLocalCellStore cellStore;
  private final PartialDataColumnSidecarSchemaFulu sidecarSchema;
  private final PartialDataColumnPartsMetadataSchema metadataSchema;

  public PartialDataColumnSidecarPublisher(
      final Gossip gossip,
      final PartialDataColumnHeaderCache headerCache,
      final PartialDataColumnLocalCellStore cellStore,
      final PartialDataColumnSidecarSchemaFulu sidecarSchema,
      final PartialDataColumnPartsMetadataSchema metadataSchema) {
    this.gossip = gossip;
    this.headerCache = headerCache;
    this.cellStore = cellStore;
    this.sidecarSchema = sidecarSchema;
    this.metadataSchema = metadataSchema;
  }

  /**
   * Reactively publishes cells to peers that have requested them (call site 1).
   *
   * <p>For each peer in {@code peerStates} that has requested cells we can serve, this builds a
   * {@link PartialDataColumnSidecarFulu} containing those cells (plus the header if the peer has
   * not yet sent us any message) and enqueues an outbound RPC via jvm-libp2p.
   *
   * @param topic the gossip topic (e.g. {@code /eth2/<fd>/data_column_sidecar_N/ssz_snappy})
   * @param blockRoot the block root from the group ID
   * @param columnIndex the data column index for this topic
   */
  public SafeFuture<Void> publishReactive(
      final String topic,
      final Bytes32 blockRoot,
      final int columnIndex,
      final Map<PeerId, PartialDataColumnPeerState> peerStates) {

    final byte[] groupId = buildGroupId(blockRoot);
    final PublishActionsFn<PartialDataColumnPeerState> actionsFn =
        (livePeerStates, peerRequestsPartial) ->
            SequencesKt.asSequence(
                buildReactiveActions(blockRoot, columnIndex, livePeerStates, peerRequestsPartial)
                    .iterator());

    return SafeFuture.of(gossip.publishPartial(topic, groupId, actionsFn)).thenApply(__ -> null);
  }

  /**
   * Publishes a metadata-only RPC to inform peers of our current cell availability (call site 2).
   *
   * <p>This is called from {@link PartialDataColumnSidecarHandler#onEmitGossip} during the gossip
   * heartbeat. Peers that have not recently received our metadata will get an updated {@link
   * PartialDataColumnPartsMetadata} showing which cells we have for this block+column.
   *
   * @param topic the gossip topic
   * @param blockRoot the block root from the group ID
   * @param columnIndex the data column index for this topic
   */
  public SafeFuture<Void> publishMetadataOnly(
      final String topic, final Bytes32 blockRoot, final int columnIndex) {

    final byte[] groupId = buildGroupId(blockRoot);
    final BitSet available = cellStore.getAvailableBitSet(blockRoot, columnIndex);
    final byte[] metadataBytes = buildMetadataBytes(available);

    final PublishActionsFn<PartialDataColumnPeerState> actionsFn =
        (peerStates, peerRequestsPartial) ->
            SequencesKt.asSequence(
                buildMetadataOnlyActions(peerStates, peerRequestsPartial, metadataBytes)
                    .iterator());

    return SafeFuture.of(gossip.publishPartial(topic, groupId, actionsFn)).thenApply(__ -> null);
  }

  // ---------------------------------------------------------------------------
  // Action builders
  // ---------------------------------------------------------------------------

  private List<Pair<PeerId, PublishAction<PartialDataColumnPeerState>>> buildReactiveActions(
      final Bytes32 blockRoot,
      final int columnIndex,
      final Map<PeerId, ? extends PartialDataColumnPeerState> peerStates,
      final Function1<? super PeerId, Boolean> peerRequestsPartial) {

    final Optional<PartialDataColumnLocalCellStore.ColumnEntry> maybeEntry =
        cellStore.get(blockRoot, columnIndex);
    final Optional<PartialDataColumnHeaderFulu> maybeHeader = headerCache.get(blockRoot);
    final BitSet available =
        maybeEntry.map(e -> (BitSet) e.available().clone()).orElseGet(BitSet::new);
    final byte[] metadataBytes = buildMetadataBytes(available);

    final List<Pair<PeerId, PublishAction<PartialDataColumnPeerState>>> actions = new ArrayList<>();

    for (final Map.Entry<PeerId, ? extends PartialDataColumnPeerState> entry :
        peerStates.entrySet()) {
      final PeerId peerId = entry.getKey();
      final PartialDataColumnPeerState state = entry.getValue();

      if (!peerRequestsPartial.invoke(peerId)) {
        continue;
      }

      // Cells the peer has requested that we haven't sent yet
      final BitSet toSend = (BitSet) state.cellsRequestedByPeer().clone();
      toSend.and(available); // only cells we actually have
      toSend.andNot(state.cellsSentToPeer()); // exclude already-sent cells

      if (toSend.isEmpty() && !state.isFirstMessage()) {
        // Nothing new to send to this peer
        continue;
      }

      // Build partial sidecar
      final Optional<byte[]> maybeSidecarBytes =
          buildPartialSidecarBytes(blockRoot, columnIndex, toSend, state, maybeHeader, maybeEntry);

      final PartialDataColumnPeerState nextState =
          state.withCellsSent(toSend).withUpdatedMetadata(buildEmptyMetadata());

      actions.add(
          new Pair<>(
              peerId,
              new PublishAction<>(maybeSidecarBytes.orElse(null), metadataBytes, nextState, null)));
    }

    return actions;
  }

  private List<Pair<PeerId, PublishAction<PartialDataColumnPeerState>>> buildMetadataOnlyActions(
      final Map<PeerId, ? extends PartialDataColumnPeerState> peerStates,
      final Function1<? super PeerId, Boolean> peerRequestsPartial,
      final byte[] metadataBytes) {

    final List<Pair<PeerId, PublishAction<PartialDataColumnPeerState>>> actions = new ArrayList<>();
    for (final Map.Entry<PeerId, ? extends PartialDataColumnPeerState> entry :
        peerStates.entrySet()) {
      final PeerId peerId = entry.getKey();
      if (peerRequestsPartial.invoke(peerId)) {
        // Send metadata only — no partialMessage payload
        actions.add(new Pair<>(peerId, new PublishAction<>(null, metadataBytes, null, null)));
      }
    }
    return actions;
  }

  // ---------------------------------------------------------------------------
  // Encoding helpers
  // ---------------------------------------------------------------------------

  private Optional<byte[]> buildPartialSidecarBytes(
      @SuppressWarnings("unused") final Bytes32 blockRoot,
      @SuppressWarnings("unused") final int columnIndex,
      final BitSet toSend,
      final PartialDataColumnPeerState state,
      final Optional<PartialDataColumnHeaderFulu> maybeHeader,
      final Optional<PartialDataColumnLocalCellStore.ColumnEntry> maybeEntry) {

    if (toSend.isEmpty() && maybeHeader.isEmpty()) {
      return Optional.empty();
    }

    // Determine cells/proofs to include
    final List<Cell> cells = new ArrayList<>();
    final List<SszKZGProof> proofs = new ArrayList<>();

    if (maybeEntry.isPresent() && !toSend.isEmpty()) {
      final PartialDataColumnLocalCellStore.ColumnEntry entry = maybeEntry.get();
      // Map from blob index to entry index
      int entryIndex = 0;
      for (int blobIndex = entry.available().nextSetBit(0);
          blobIndex >= 0;
          blobIndex = entry.available().nextSetBit(blobIndex + 1)) {
        if (toSend.get(blobIndex)) {
          cells.add(entry.cells().get(entryIndex));
          proofs.add(entry.proofs().get(entryIndex));
        }
        entryIndex++;
      }
    }

    // Build bitmap
    final int bitmapSize = Math.max(toSend.length(), 1);
    final int[] setBits = toSend.stream().toArray();
    final var bitmap =
        setBits.length == 0
            ? sidecarSchema.getCellsPresentBitmapSchema().ofBits(bitmapSize)
            : sidecarSchema.getCellsPresentBitmapSchema().ofBits(bitmapSize, setBits);
    final var cellsList = sidecarSchema.getPartialColumnSchema().createFromElements(cells);
    final var proofsList = sidecarSchema.getKzgProofsSchema().createFromElements(proofs);

    // Include header for peers that haven't messaged us yet (eager-push §9.1)
    final var headerList =
        state.isFirstMessage() && maybeHeader.isPresent()
            ? sidecarSchema.getHeaderSchema().createFromElements(List.of(maybeHeader.get()))
            : sidecarSchema.getHeaderSchema().createFromElements(List.of());

    final PartialDataColumnSidecarFulu sidecar =
        sidecarSchema.create(bitmap, cellsList, proofsList, headerList);
    return Optional.of(sidecar.sszSerialize().toArrayUnsafe());
  }

  private byte[] buildMetadataBytes(final BitSet available) {
    // available bits = cells we have; requests = empty (we're not requesting from peers here)
    final int size = Math.max(available.length(), 1);
    final PartialDataColumnPartsMetadata metadata =
        metadataSchema.create(
            metadataSchema.getAvailableSchema().ofBits(size, available.stream().toArray()),
            metadataSchema.getRequestsSchema().ofBits(size));
    return metadata.sszSerialize().toArrayUnsafe();
  }

  private PartialDataColumnPartsMetadata buildEmptyMetadata() {
    return metadataSchema.create(
        metadataSchema.getAvailableSchema().ofBits(0),
        metadataSchema.getRequestsSchema().ofBits(0));
  }

  private static byte[] buildGroupId(final Bytes32 blockRoot) {
    final byte[] groupId = new byte[33];
    groupId[0] = GROUP_ID_VERSION_BYTE;
    System.arraycopy(blockRoot.toArrayUnsafe(), 0, groupId, 1, 32);
    return groupId;
  }
}

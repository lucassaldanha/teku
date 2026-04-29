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
import io.libp2p.pubsub.gossip.partialmessages.PublishAction;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.networking.eth2.gossip.partialmessages.jvmlibp2p.PartialGossip;
import tech.pegasys.teku.networking.eth2.gossip.partialmessages.jvmlibp2p.PublishActionsFn;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.Cell;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.DataColumnSidecarFulu;
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
 * <p>Uses {@link PartialGossip} as an indirection over {@code io.libp2p.pubsub.gossip.Gossip} so
 * that the real implementation can be injected after the network is built.
 */
public class PartialDataColumnSidecarPublisher {

  private static final Logger LOG = LogManager.getLogger();
  private static final byte GROUP_ID_VERSION_BYTE = 0x00;

  private volatile PartialGossip partialGossip;
  private final PartialDataColumnHeaderCache headerCache;
  private final PartialDataColumnLocalCellStore cellStore;
  private final PartialDataColumnSidecarSchemaFulu sidecarSchema;
  private final PartialDataColumnPartsMetadataSchema metadataSchema;

  public PartialDataColumnSidecarPublisher(
      final PartialGossip partialGossip,
      final PartialDataColumnHeaderCache headerCache,
      final PartialDataColumnLocalCellStore cellStore,
      final PartialDataColumnSidecarSchemaFulu sidecarSchema,
      final PartialDataColumnPartsMetadataSchema metadataSchema) {
    this.partialGossip = partialGossip;
    this.headerCache = headerCache;
    this.cellStore = cellStore;
    this.sidecarSchema = sidecarSchema;
    this.metadataSchema = metadataSchema;
  }

  public void setPartialGossip(final PartialGossip partialGossip) {
    this.partialGossip = partialGossip;
  }

  /**
   * Reactively publishes cells to peers that have requested them (call site 1).
   *
   * @param topic the gossip topic (e.g. {@code /eth2/<fd>/data_column_sidecar_N/ssz_snappy})
   * @param blockRoot the block root from the group ID
   * @param columnIndex the data column index for this topic
   * @param peerStates snapshot of current peer states (not required to call publishPartial; the
   *     live map is passed via the actionsFn)
   */
  public SafeFuture<Void> publishReactive(
      final String topic,
      final Bytes32 blockRoot,
      final int columnIndex,
      @SuppressWarnings("unused") final Map<PeerId, PartialDataColumnPeerState> peerStates) {

    LOG.debug(
        "publishReactive: topic={} blockRoot={} column={} peers={}",
        topic,
        blockRoot,
        columnIndex,
        peerStates.size());
    final byte[] groupId = buildGroupId(blockRoot);
    final PublishActionsFn<PartialDataColumnPeerState> actionsFn =
        (livePeerStates, peerRequestsPartial) -> {
          final var actions =
              buildReactiveActions(blockRoot, columnIndex, livePeerStates, peerRequestsPartial);
          LOG.trace(
              "publishReactive actionsFn: blockRoot={} column={} building {} peer actions",
              blockRoot,
              columnIndex,
              actions.size());
          return actions.stream()
              .map(
                  entry ->
                      (Map.Entry<PeerId, PublishAction<PartialDataColumnPeerState>>)
                          new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
        };

    return SafeFuture.of(partialGossip.publishPartial(topic, groupId, actionsFn));
  }

  /**
   * Publishes a metadata-only RPC to inform peers of our current cell availability (call site 2).
   *
   * @param topic the gossip topic
   * @param blockRoot the block root from the group ID
   * @param columnIndex the data column index for this topic
   */
  public SafeFuture<Void> publishMetadataOnly(
      final String topic, final Bytes32 blockRoot, final int columnIndex) {

    final byte[] groupId = buildGroupId(blockRoot);
    final BitSet available = cellStore.getAvailableBitSet(blockRoot, columnIndex);
    LOG.debug(
        "publishMetadataOnly: topic={} blockRoot={} column={} availableCells={}",
        topic,
        blockRoot,
        columnIndex,
        available.cardinality());
    final byte[] metadataBytes = buildMetadataBytes(available);

    final PublishActionsFn<PartialDataColumnPeerState> actionsFn =
        (peerStates, peerRequestsPartial) ->
            buildMetadataOnlyActions(peerStates, peerRequestsPartial, metadataBytes).stream()
                .map(
                    entry ->
                        (Map.Entry<PeerId, PublishAction<PartialDataColumnPeerState>>)
                            new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));

    return SafeFuture.of(partialGossip.publishPartial(topic, groupId, actionsFn));
  }

  /**
   * Sends metadata to ALL partial-capable mesh peers for this topic/group (call site: heartbeat
   * onEmitGossip). Unlike {@link #publishMetadataOnly}, this includes peers not yet tracked in the
   * group state by initialising them with {@link PartialDataColumnPeerState#initial()}.
   */
  public SafeFuture<Void> publishMetadataToGossipPeers(
      final String topic,
      final Bytes32 blockRoot,
      final int columnIndex,
      final Collection<PeerId> gossipPeers) {
    final byte[] groupId = buildGroupId(blockRoot);
    final BitSet available = cellStore.getAvailableBitSet(blockRoot, columnIndex);
    LOG.debug(
        "publishMetadataToGossipPeers: topic={} blockRoot={} column={} peers={} available={}",
        topic,
        blockRoot,
        columnIndex,
        gossipPeers.size(),
        available.cardinality());
    final byte[] metadataBytes = buildMetadataBytes(available);

    final PublishActionsFn<PartialDataColumnPeerState> actionsFn =
        (livePeerStates, peerRequestsPartial) -> {
          final List<Map.Entry<PeerId, PublishAction<PartialDataColumnPeerState>>> actions =
              new ArrayList<>();
          for (final Map.Entry<PeerId, ? extends PartialDataColumnPeerState> entry :
              livePeerStates.entrySet()) {
            if (peerRequestsPartial.apply(entry.getKey())) {
              actions.add(
                  new AbstractMap.SimpleEntry<>(
                      entry.getKey(), new PublishAction<>(null, metadataBytes, null, null)));
            }
          }
          for (final PeerId peerId : gossipPeers) {
            if (!livePeerStates.containsKey(peerId) && peerRequestsPartial.apply(peerId)) {
              // New peer: initialise state so jvm-libp2p tracks it in the group
              actions.add(
                  new AbstractMap.SimpleEntry<>(
                      peerId,
                      new PublishAction<>(
                          null, metadataBytes, PartialDataColumnPeerState.initial(), null)));
            }
          }
          return actions.stream();
        };
    return SafeFuture.of(partialGossip.publishPartial(topic, groupId, actionsFn));
  }

  /**
   * Called when this node proposes a block: stores all cells from {@code fullSidecar} in the local
   * cell store and creates the partial-messages group. The next gossipsub heartbeat will fire
   * {@link PartialDataColumnSidecarHandler#onEmitGossip} which announces availability to peers.
   */
  public SafeFuture<Void> eagerPushFromProposer(
      final String topic, final DataColumnSidecarFulu fullSidecar) {
    final Bytes32 blockRoot = fullSidecar.getSignedBlockHeader().getMessage().hashTreeRoot();
    final int columnIndex = fullSidecar.getIndex().intValue();
    final int numCells = fullSidecar.getColumn().size();

    LOG.debug(
        "eagerPushFromProposer: topic={} blockRoot={} column={} cells={}",
        topic,
        blockRoot,
        columnIndex,
        numCells);

    if (numCells > 0) {
      final int[] allBits = IntStream.range(0, numCells).toArray();
      final var bitmap = sidecarSchema.getCellsPresentBitmapSchema().ofBits(numCells, allBits);
      final var cells =
          sidecarSchema
              .getPartialColumnSchema()
              .createFromElements(fullSidecar.getColumn().asList());
      final var proofs =
          sidecarSchema
              .getKzgProofsSchema()
              .createFromElements(fullSidecar.getKzgProofs().asList());
      final var emptyHeader = sidecarSchema.getHeaderSchema().createFromElements(List.of());
      cellStore.merge(
          blockRoot, columnIndex, sidecarSchema.create(bitmap, cells, proofs, emptyHeader));
    }

    // Creates the partial-messages group; peers will be notified on the next heartbeat
    return publishMetadataOnly(topic, blockRoot, columnIndex);
  }

  // ---------------------------------------------------------------------------
  // Action builders
  // ---------------------------------------------------------------------------

  private List<Map.Entry<PeerId, PublishAction<PartialDataColumnPeerState>>> buildReactiveActions(
      final Bytes32 blockRoot,
      final int columnIndex,
      final Map<PeerId, ? extends PartialDataColumnPeerState> peerStates,
      final Function<PeerId, Boolean> peerRequestsPartial) {

    final Optional<PartialDataColumnLocalCellStore.ColumnEntry> maybeEntry =
        cellStore.get(blockRoot, columnIndex);
    final Optional<PartialDataColumnHeaderFulu> maybeHeader = headerCache.get(blockRoot);
    final BitSet available =
        maybeEntry.map(e -> (BitSet) e.available().clone()).orElseGet(BitSet::new);
    final byte[] metadataBytes = buildMetadataBytes(available);

    final List<Map.Entry<PeerId, PublishAction<PartialDataColumnPeerState>>> actions =
        new ArrayList<>();

    for (final Map.Entry<PeerId, ? extends PartialDataColumnPeerState> entry :
        peerStates.entrySet()) {
      final PeerId peerId = entry.getKey();
      final PartialDataColumnPeerState state = entry.getValue();

      if (!peerRequestsPartial.apply(peerId)) {
        continue;
      }

      final BitSet toSend = (BitSet) state.cellsRequestedByPeer().clone();
      toSend.and(available);
      toSend.andNot(state.cellsSentToPeer());

      if (toSend.isEmpty() && !state.isFirstMessage()) {
        LOG.trace(
            "publishReactive: peer={} blockRoot={} column={} — nothing new to send; skipping",
            peerId,
            blockRoot,
            columnIndex);
        continue;
      }

      LOG.trace(
          "publishReactive: peer={} blockRoot={} column={} — sending {} cell(s) includeHeader={}",
          peerId,
          blockRoot,
          columnIndex,
          toSend.cardinality(),
          state.isFirstMessage());

      final Optional<byte[]> maybeSidecarBytes =
          buildPartialSidecarBytes(blockRoot, columnIndex, toSend, state, maybeHeader, maybeEntry);

      final PartialDataColumnPeerState nextState =
          state.withCellsSent(toSend).withUpdatedMetadata(buildEmptyMetadata());

      actions.add(
          new AbstractMap.SimpleEntry<>(
              peerId,
              new PublishAction<>(maybeSidecarBytes.orElse(null), metadataBytes, nextState, null)));
    }

    return actions;
  }

  private List<Map.Entry<PeerId, PublishAction<PartialDataColumnPeerState>>>
      buildMetadataOnlyActions(
          final Map<PeerId, ? extends PartialDataColumnPeerState> peerStates,
          final Function<PeerId, Boolean> peerRequestsPartial,
          final byte[] metadataBytes) {

    final List<Map.Entry<PeerId, PublishAction<PartialDataColumnPeerState>>> actions =
        new ArrayList<>();
    for (final Map.Entry<PeerId, ? extends PartialDataColumnPeerState> entry :
        peerStates.entrySet()) {
      final PeerId peerId = entry.getKey();
      if (peerRequestsPartial.apply(peerId)) {
        actions.add(
            new AbstractMap.SimpleEntry<>(
                peerId, new PublishAction<>(null, metadataBytes, null, null)));
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

    final List<Cell> cells = new ArrayList<>();
    final List<SszKZGProof> proofs = new ArrayList<>();

    if (maybeEntry.isPresent() && !toSend.isEmpty()) {
      final PartialDataColumnLocalCellStore.ColumnEntry entry = maybeEntry.get();
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

    final int bitmapSize = Math.max(toSend.length(), 1);
    final int[] setBits = toSend.stream().toArray();
    final var bitmap =
        setBits.length == 0
            ? sidecarSchema.getCellsPresentBitmapSchema().ofBits(bitmapSize)
            : sidecarSchema.getCellsPresentBitmapSchema().ofBits(bitmapSize, setBits);
    final var cellsList = sidecarSchema.getPartialColumnSchema().createFromElements(cells);
    final var proofsList = sidecarSchema.getKzgProofsSchema().createFromElements(proofs);

    final var headerList =
        state.isFirstMessage() && maybeHeader.isPresent()
            ? sidecarSchema.getHeaderSchema().createFromElements(List.of(maybeHeader.get()))
            : sidecarSchema.getHeaderSchema().createFromElements(List.of());

    final PartialDataColumnSidecarFulu sidecar =
        sidecarSchema.create(bitmap, cellsList, proofsList, headerList);
    return Optional.of(sidecar.sszSerialize().toArrayUnsafe());
  }

  private byte[] buildMetadataBytes(final BitSet available) {
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

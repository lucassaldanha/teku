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
import io.libp2p.pubsub.gossip.partialmessages.FeedbackKind;
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesHandler;
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesPeerFeedback;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import pubsub.pb.Rpc;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnPartsMetadata;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnPartsMetadata.PartialDataColumnPartsMetadataSchema;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarSchemaFulu;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;
import tech.pegasys.teku.statetransition.validation.PartialDataColumnHeaderGossipValidator;
import tech.pegasys.teku.statetransition.validation.PartialDataColumnSidecarGossipValidator;

/**
 * Inbound handler for the partial-messages extension on {@code data_column_sidecar_{subnet_id}}
 * topics, implementing {@link PartialMessagesHandler}.
 *
 * <p>{@link #onIncomingRpc} runs on the pubsub event thread and must be fast and non-blocking; all
 * real work (SSZ decoding, KZG verification, reconstruction) is dispatched to the supplied {@link
 * AsyncRunner}.
 *
 * <p>{@link #onEmitGossip} is stubbed in this step (Step 6). The publisher implementation follows
 * in Step 7.
 */
public class PartialDataColumnSidecarHandler
    implements PartialMessagesHandler<PartialDataColumnPeerState> {

  private static final Logger LOG = LogManager.getLogger();

  /** Group IDs are {@code 0x00 ‖ blockRoot}. We only support version byte 0x00. */
  private static final byte SUPPORTED_VERSION_BYTE = 0x00;

  private static final int GROUP_ID_LENGTH = 33; // 1 version byte + 32 bytes block root
  private static final Pattern COLUMN_INDEX_PATTERN = Pattern.compile("data_column_sidecar_(\\d+)");

  private final AsyncRunner asyncRunner;
  private final PartialDataColumnHeaderGossipValidator headerValidator;
  private final PartialDataColumnSidecarGossipValidator cellValidator;
  private final PartialDataColumnLocalCellStore cellStore;
  private final PartialDataColumnPartsMetadataSchema metadataSchema;
  private final PartialDataColumnSidecarSchemaFulu sidecarSchema;
  private final ReconstructionListener reconstructionListener;

  private volatile Optional<PartialDataColumnSidecarPublisher> publisher = Optional.empty();

  /**
   * Callback invoked when all cells for a {@code (blockRoot, columnIndex)} pair have been received.
   * Step 9 will replace this with the actual reconstruction + forwarding logic.
   */
  @FunctionalInterface
  public interface ReconstructionListener {
    void onReconstructionComplete(
        Bytes32 blockRoot, int columnIndex, PartialDataColumnLocalCellStore.ColumnEntry entry);
  }

  public PartialDataColumnSidecarHandler(
      final AsyncRunner asyncRunner,
      final PartialDataColumnHeaderGossipValidator headerValidator,
      final PartialDataColumnSidecarGossipValidator cellValidator,
      final PartialDataColumnLocalCellStore cellStore,
      final PartialDataColumnPartsMetadataSchema metadataSchema,
      final PartialDataColumnSidecarSchemaFulu sidecarSchema,
      final ReconstructionListener reconstructionListener) {
    this.asyncRunner = asyncRunner;
    this.headerValidator = headerValidator;
    this.cellValidator = cellValidator;
    this.cellStore = cellStore;
    this.metadataSchema = metadataSchema;
    this.sidecarSchema = sidecarSchema;
    this.reconstructionListener = reconstructionListener;
  }

  public void setPublisher(final PartialDataColumnSidecarPublisher publisher) {
    this.publisher = Optional.of(publisher);
  }

  /**
   * Called on the pubsub event thread for every inbound partial-messages RPC.
   *
   * <p>Performs only fast, synchronous pre-checks (version byte, SSZ decode), then dispatches to
   * the async runner.
   */
  @Override
  public void onIncomingRpc(
      final PeerId from,
      final Map<PeerId, ? extends PartialDataColumnPeerState> peerStates,
      final Rpc.PartialMessagesExtension rpc,
      final PartialMessagesPeerFeedback feedback) {

    LOG.trace(
        "onIncomingRpc: peer={} topic={} hasPartialMsg={} hasPartsMetadata={}",
        from,
        rpc.getTopicID(),
        rpc.hasPartialMessage(),
        rpc.hasPartsMetadata());

    // 1. Decode and validate groupId version byte
    final byte[] groupIdBytes = rpc.getGroupID().toByteArray();
    if (groupIdBytes.length != GROUP_ID_LENGTH || groupIdBytes[0] != SUPPORTED_VERSION_BYTE) {
      LOG.debug(
          "Partial message from {} has invalid group ID (length={}, versionByte={}); dropping",
          from,
          groupIdBytes.length,
          groupIdBytes.length > 0 ? groupIdBytes[0] : -1);
      return;
    }
    final Bytes32 blockRoot = Bytes32.wrap(groupIdBytes, 1);

    // 2. Extract column index from topic
    final int columnIndex = extractColumnIndex(rpc.getTopicID());
    if (columnIndex < 0) {
      LOG.debug(
          "Partial message from {} has unrecognised topic '{}'; dropping", from, rpc.getTopicID());
      return;
    }

    // 3. Snapshot peer states (live view must not be retained beyond this call)
    @SuppressWarnings("unchecked")
    final Map<PeerId, PartialDataColumnPeerState> peerStateSnapshot =
        new HashMap<>((Map<PeerId, PartialDataColumnPeerState>) (Map<?, ?>) peerStates);

    // 4. SSZ-decode partsMetadata and partialMessage (synchronous but cheap)
    final Optional<PartialDataColumnPartsMetadata> maybeMetadata = decodeMetadata(rpc);
    final Optional<PartialDataColumnSidecarFulu> maybeSidecar = decodeSidecar(rpc);

    LOG.debug(
        "Partial message received: peer={} blockRoot={} column={} hasHeader={} cellCount={}"
            + " hasMetadata={}",
        from,
        blockRoot,
        columnIndex,
        maybeSidecar.map(s -> s.getOptionalHeader().isPresent()).orElse(false),
        maybeSidecar.map(s -> s.getCellsPresentBitmap().getBitCount()).orElse(0),
        maybeMetadata.isPresent());

    // 5. Dispatch real work to async runner
    asyncRunner
        .runAsync(
            () ->
                processAsync(
                    from,
                    peerStateSnapshot,
                    blockRoot,
                    columnIndex,
                    rpc.getTopicID(),
                    maybeMetadata,
                    maybeSidecar,
                    feedback))
        .finish(
            error ->
                LOG.error(
                    "Unhandled error processing partial message from {} for blockRoot {}",
                    from,
                    blockRoot,
                    error));
  }

  @Override
  public void onEmitGossip(
      final String topic,
      final byte[] groupId,
      final Collection<PeerId> gossipPeers,
      final Map<PeerId, ? extends PartialDataColumnPeerState> peerStates,
      final PartialMessagesPeerFeedback feedback) {
    LOG.trace(
        "onEmitGossip: topic={} groupId={} gossipPeers={}", topic, groupId, gossipPeers.size());

    if (publisher.isEmpty()) {
      return;
    }
    if (groupId.length != GROUP_ID_LENGTH || groupId[0] != SUPPORTED_VERSION_BYTE) {
      return;
    }
    final Bytes32 blockRoot = Bytes32.wrap(groupId, 1);
    final int columnIndex = extractColumnIndex(topic);
    if (columnIndex < 0) {
      return;
    }

    final PartialDataColumnSidecarPublisher p = publisher.get();
    asyncRunner
        .runAsync(() -> p.publishMetadataToGossipPeers(topic, blockRoot, columnIndex, gossipPeers))
        .finish(error -> LOG.error("Error in publishMetadataToGossipPeers", error));
  }

  // ---------------------------------------------------------------------------
  // Async processing (runs on asyncRunner, not on pubsub event thread)
  // ---------------------------------------------------------------------------

  private void processAsync(
      final PeerId from,
      @SuppressWarnings("unused") final Map<PeerId, PartialDataColumnPeerState> peerStates,
      final Bytes32 blockRoot,
      final int columnIndex,
      final String topicId,
      final Optional<PartialDataColumnPartsMetadata> maybeMetadata,
      final Optional<PartialDataColumnSidecarFulu> maybeSidecar,
      final PartialMessagesPeerFeedback feedback) {

    // a. Validate header if present
    if (maybeSidecar.isPresent() && maybeSidecar.get().getOptionalHeader().isPresent()) {
      LOG.trace(
          "Validating partial message header from {} for blockRoot={} column={}",
          from,
          blockRoot,
          columnIndex);
      final InternalValidationResult headerResult =
          headerValidator.validate(maybeSidecar.get(), blockRoot).join();

      if (headerResult.isReject() || headerResult.isIgnore()) {
        if (headerResult.isReject()) {
          LOG.debug(
              "Partial message header from {} for blockRoot={} column={} REJECTED: {}",
              from,
              blockRoot,
              columnIndex,
              headerResult.getDescription().orElse("no reason"));
          feedback.reportFeedback(topicId, from, FeedbackKind.INVALID);
        } else {
          LOG.trace(
              "Partial message header from {} for blockRoot={} column={} IGNORED: {}",
              from,
              blockRoot,
              columnIndex,
              headerResult.getDescription().orElse("no reason"));
          feedback.reportFeedback(topicId, from, FeedbackKind.IGNORED);
        }
        return;
      }
      LOG.trace(
          "Partial message header from {} for blockRoot={} column={} accepted and cached",
          from,
          blockRoot,
          columnIndex);
    }

    // b. Validate and process cells if present
    if (maybeSidecar.isPresent() && !maybeSidecar.get().getCellsPresentBitmap().isEmpty()) {
      final PartialDataColumnSidecarFulu sidecar = maybeSidecar.get();
      final int incomingCellCount = sidecar.getCellsPresentBitmap().getBitCount();

      LOG.trace(
          "Validating {} cells from {} for blockRoot={} column={}",
          incomingCellCount,
          from,
          blockRoot,
          columnIndex);

      final InternalValidationResult cellResult =
          cellValidator.validate(sidecar, blockRoot, columnIndex).join();

      if (cellResult.isReject()) {
        LOG.debug(
            "Partial message cells from {} for blockRoot={} column={} REJECTED ({}): {}",
            from,
            blockRoot,
            columnIndex,
            incomingCellCount,
            cellResult.getDescription().orElse("no reason"));
        feedback.reportFeedback(topicId, from, FeedbackKind.INVALID);
        return;
      }

      if (cellResult.isIgnore()) {
        LOG.trace(
            "Partial message cells from {} for blockRoot={} column={} IGNORED: {}",
            from,
            blockRoot,
            columnIndex,
            cellResult.getDescription().orElse("no reason"));
        feedback.reportFeedback(topicId, from, FeedbackKind.IGNORED);
        return;
      }

      // Merge novel cells into the local store
      final List<Integer> novelBlobIndices = cellStore.merge(blockRoot, columnIndex, sidecar);
      if (novelBlobIndices.isEmpty()) {
        LOG.trace(
            "All {} cells from {} for blockRoot={} column={} are duplicates",
            incomingCellCount,
            from,
            blockRoot,
            columnIndex);
        feedback.reportFeedback(topicId, from, FeedbackKind.IGNORED);
      } else {
        LOG.debug(
            "Merged {} novel cell(s) from {} for blockRoot={} column={} blobIndices={}",
            novelBlobIndices.size(),
            from,
            blockRoot,
            columnIndex,
            novelBlobIndices);
        feedback.reportFeedback(topicId, from, FeedbackKind.USEFUL);

        // Forward novel cells to other peers that have requested them
        publisher.ifPresent(
            p ->
                p.publishReactive(topicId, blockRoot, columnIndex, peerStates)
                    .finish(
                        error -> LOG.error("Error in publishReactive after cell merge", error)));

        // d. Check if reconstruction is possible
        cellStore
            .get(blockRoot, columnIndex)
            .ifPresent(
                entry -> {
                  LOG.trace(
                      "Cell store for blockRoot={} column={}: have {} cells so far",
                      blockRoot,
                      columnIndex,
                      entry.cellCount());
                  final int totalExpected = computeTotalExpected(blockRoot, entry);
                  if (totalExpected > 0 && entry.cellCount() == totalExpected) {
                    LOG.debug(
                        "All {} cells complete for blockRoot={} column={} — triggering reconstruction",
                        totalExpected,
                        blockRoot,
                        columnIndex);
                    reconstructionListener.onReconstructionComplete(blockRoot, columnIndex, entry);
                  }
                });
      }
    }

    // c. Update peer state via future peer-state update mechanism (Step 7)
    maybeMetadata.ifPresent(
        metadata -> {
          // Peer state update will be applied atomically via PublishAction.nextPeerState in Step 7
          LOG.trace(
              "Peer {} metadata for blockRoot={} column={}: available={} cells, requests={} cells",
              from,
              blockRoot,
              columnIndex,
              metadata.getAvailable().getBitCount(),
              metadata.getRequests().getBitCount());
        });
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private int extractColumnIndex(final String topicId) {
    final Matcher matcher = COLUMN_INDEX_PATTERN.matcher(topicId);
    if (!matcher.find()) {
      return -1;
    }
    try {
      return Integer.parseInt(matcher.group(1));
    } catch (final NumberFormatException e) {
      return -1;
    }
  }

  private Optional<PartialDataColumnPartsMetadata> decodeMetadata(
      final Rpc.PartialMessagesExtension rpc) {
    if (!rpc.hasPartsMetadata()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          metadataSchema.sszDeserialize(Bytes.wrap(rpc.getPartsMetadata().toByteArray())));
    } catch (final Exception e) {
      LOG.debug("Failed to SSZ-decode partsMetadata: {}", e.getMessage());
      return Optional.empty();
    }
  }

  private Optional<PartialDataColumnSidecarFulu> decodeSidecar(
      final Rpc.PartialMessagesExtension rpc) {
    if (!rpc.hasPartialMessage()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          sidecarSchema.sszDeserialize(Bytes.wrap(rpc.getPartialMessage().toByteArray())));
    } catch (final Exception e) {
      LOG.debug("Failed to SSZ-decode partialMessage: {}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Infers the total expected cell count from the header cache or the entry itself. Returns 0 if
   * the expected count cannot be determined (e.g. header not yet cached).
   *
   * <p>In the full implementation (Step 6+) the header provides {@code len(kzg_commitments)} which
   * is the total. For now we use the entry's current cardinality as a conservative lower-bound
   * heuristic.
   */
  private int computeTotalExpected(
      @SuppressWarnings("unused") final Bytes32 blockRoot,
      @SuppressWarnings("unused") final PartialDataColumnLocalCellStore.ColumnEntry entry) {
    // The accurate count comes from the cached header's kzg_commitments length.
    // This is filled in by the header validator writing to the shared header cache.
    // The cell store's available bitset grows monotonically, so the cardinality check
    // is: complete when available.cardinality() == kzg_commitments.size().
    //
    // For Step 6 we return the current count only when it matches a stored
    // reconstruction threshold. Real reconstruction (Step 9) will wire the
    // header cache lookup here.
    return 0; // placeholder: step 9 will supply the real value from the header cache
  }
}

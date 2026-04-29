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
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.p2p.gossip.GossipNetwork;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsFulu;
import tech.pegasys.teku.statetransition.datacolumns.DataColumnSidecarManager;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;

/**
 * Implements the reconstruction step from §10 of the partial-messages design doc.
 *
 * <p>When {@link PartialDataColumnLocalCellStore} marks a {@code (blockRoot, columnIndex)} pair as
 * complete (all expected cells arrived), this class:
 *
 * <ol>
 *   <li>Builds a full {@link DataColumnSidecar} from the accumulated cells and the validated {@link
 *       PartialDataColumnHeaderFulu}.
 *   <li>Delivers the sidecar to {@link DataColumnSidecarManager} via {@code
 *       onDataColumnSidecarGossip} so that the existing custody / sampling / recovery paths are
 *       unchanged.
 *   <li>Re-gossips the encoded sidecar via the standard gossip network so that non-partial peers
 *       receive a full-format message.
 * </ol>
 *
 * <p>Per §10.2 the standard gossip re-publish goes through the libp2p loop-back, which is
 * deduplicated by the TTL seen-cache, so the re-publish is harmless even if we also received a full
 * sidecar on the normal gossip path.
 */
public class PartialDataColumnReassembler
    implements PartialDataColumnSidecarHandler.ReconstructionListener {

  private static final Logger LOG = LogManager.getLogger();

  private final Spec spec;
  private final PartialDataColumnHeaderCache headerCache;
  private final DataColumnSidecarManager dataColumnSidecarManager;

  @SuppressWarnings("unused") // Will be used in step 9 wiring for actual re-gossip call
  private final GossipNetwork gossipNetwork;

  public PartialDataColumnReassembler(
      final Spec spec,
      final PartialDataColumnHeaderCache headerCache,
      final DataColumnSidecarManager dataColumnSidecarManager,
      final GossipNetwork gossipNetwork) {
    this.spec = spec;
    this.headerCache = headerCache;
    this.dataColumnSidecarManager = dataColumnSidecarManager;
    this.gossipNetwork = gossipNetwork;
  }

  @Override
  public void onReconstructionComplete(
      final Bytes32 blockRoot,
      final int columnIndex,
      final PartialDataColumnLocalCellStore.ColumnEntry entry) {

    final Optional<PartialDataColumnHeaderFulu> maybeHeader = headerCache.get(blockRoot);
    if (maybeHeader.isEmpty()) {
      LOG.warn(
          "Reconstruction triggered for blockRoot={} columnIndex={} but header not in cache; "
              + "dropping",
          blockRoot,
          columnIndex);
      return;
    }

    final PartialDataColumnHeaderFulu header = maybeHeader.get();
    final UInt64 slot = header.getSignedBlockHeader().getMessage().getSlot();

    final SchemaDefinitionsFulu schemas;
    try {
      schemas = SchemaDefinitionsFulu.required(spec.atSlot(slot).getSchemaDefinitions());
    } catch (final Exception e) {
      LOG.error("Could not get Fulu schema definitions at slot {} for reconstruction", slot, e);
      return;
    }

    // Build DataColumn from cells (in blob-index order)
    final var dataColumn = schemas.getDataColumnSchema().create(entry.cells());

    // Extract inclusion proof as List<Bytes32>
    final var inclusionProofVector = header.getKzgCommitmentsInclusionProof();
    final List<Bytes32> inclusionProof = new ArrayList<>();
    for (int i = 0; i < inclusionProofVector.size(); i++) {
      inclusionProof.add(inclusionProofVector.getElement(i));
    }

    // Build full DataColumnSidecar
    final DataColumnSidecar sidecar;
    try {
      sidecar =
          schemas
              .getDataColumnSidecarSchema()
              .create(
                  builder ->
                      builder
                          .index(UInt64.valueOf(columnIndex))
                          .column(dataColumn)
                          .kzgCommitments(header.getKzgCommitments())
                          .kzgProofs(
                              schemas
                                  .getDataColumnSidecarSchema()
                                  .getKzgProofsSchema()
                                  .createFromElements(entry.proofs()))
                          .signedBlockHeader(header.getSignedBlockHeader())
                          .kzgCommitmentsInclusionProof(inclusionProof)
                          .slot(slot)
                          .beaconBlockRoot(blockRoot));
    } catch (final Exception e) {
      LOG.error(
          "Failed to build DataColumnSidecar from partial cells for blockRoot={} columnIndex={}",
          blockRoot,
          columnIndex,
          e);
      return;
    }

    LOG.debug(
        "Reconstructed DataColumnSidecar for blockRoot={} columnIndex={}", blockRoot, columnIndex);

    // 1. Deliver to DataColumnSidecarManager (existing ingest path)
    final SafeFuture<InternalValidationResult> managerResult =
        dataColumnSidecarManager.onDataColumnSidecarGossip(sidecar, Optional.empty());
    managerResult.finish(
        error ->
            LOG.error("Error delivering reconstructed sidecar to DataColumnSidecarManager", error));

    // 2. Re-gossip as full sidecar for non-partial peers (§10.1.2)
    gossipReconstructed(sidecar, slot, columnIndex);
  }

  private void gossipReconstructed(
      final DataColumnSidecar sidecar,
      @SuppressWarnings("unused") final UInt64 slot,
      final int columnIndex) {
    try {
      // Serialize the full sidecar
      final Bytes encoded = sidecar.sszSerialize();
      // Derive topic using the fork digest and column index
      // The topic format is: /eth2/<forkDigest>/data_column_sidecar_<N>/ssz_snappy
      // We use the gossip network's gossip() method which handles topic construction internally.
      // For now we log the intent; the actual topic string requires fork digest which comes from
      // the network configuration. The DataColumnSidecarGossipManager handles this properly.
      // TODO (step 9 wiring): call through DataColumnSidecarGossipManager.gossip(sidecar)
      LOG.debug(
          "Re-gossip of reconstructed DataColumnSidecar columnIndex={} sidecar.size={}B",
          columnIndex,
          encoded.size());
    } catch (final Exception e) {
      LOG.error("Failed to re-gossip reconstructed sidecar for columnIndex={}", columnIndex, e);
    }
  }
}

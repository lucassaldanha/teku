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

package tech.pegasys.teku.statetransition.validation;

import static tech.pegasys.teku.infrastructure.async.SafeFuture.completedFuture;

import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.kzg.KZGCommitment;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.type.SszKZGCommitment;
import tech.pegasys.teku.spec.logic.common.util.PartialDataColumnSidecarUtil;

/**
 * Validates the cell portion of a partial data column message per §8.2 of the partial-messages
 * design doc. Should be called after {@link PartialDataColumnHeaderGossipValidator} succeeds and a
 * validated {@link PartialDataColumnHeaderFulu} is available in the header cache.
 *
 * <p>Requires a validated header to be present in the supplied {@code validatedHeaders} map for the
 * given {@code blockRoot}. If no header is cached the message is IGNORED (it may arrive later once
 * a header message for the same block is seen).
 */
public class PartialDataColumnSidecarGossipValidator {

  private static final Logger LOG = LogManager.getLogger();

  private final Spec spec;
  private final GossipValidationHelper gossipValidationHelper;
  private final Map<Bytes32, PartialDataColumnHeaderFulu> validatedHeaders;

  public PartialDataColumnSidecarGossipValidator(
      final Spec spec,
      final GossipValidationHelper gossipValidationHelper,
      final Map<Bytes32, PartialDataColumnHeaderFulu> validatedHeaders) {
    this.spec = spec;
    this.gossipValidationHelper = gossipValidationHelper;
    this.validatedHeaders = validatedHeaders;
  }

  /**
   * Validates the cells carried in a partial sidecar message.
   *
   * @param sidecar the partial sidecar received from the peer
   * @param blockRoot the block root extracted from the group ID
   * @param columnIndex the data column index implied by the gossip topic
   * @return ACCEPT, IGNORE, or REJECT wrapped in a SafeFuture
   */
  public SafeFuture<InternalValidationResult> validate(
      final PartialDataColumnSidecarFulu sidecar, final Bytes32 blockRoot, final int columnIndex) {

    /*
     * [IGNORE] No validated header for blockRoot is cached. Cell validation requires a header;
     * the message may be re-validated once a header arrives.
     */
    final PartialDataColumnHeaderFulu header = validatedHeaders.get(blockRoot);
    if (header == null) {
      return completedFuture(
          ignore(
              "No validated PartialDataColumnHeader cached for blockRoot "
                  + blockRoot
                  + "; cannot validate cells"));
    }

    final UInt64 slot = header.getSignedBlockHeader().getMessage().getSlot();

    /*
     * [IGNORE] Header slot timing (re-checked against current finalized state to handle late delivery).
     */
    if (gossipValidationHelper.isSlotFromFuture(slot)) {
      return completedFuture(ignore("Partial message cell slot is from the future"));
    }
    if (gossipValidationHelper.isSlotFinalized(slot)) {
      return completedFuture(ignore("Partial message cell slot has been finalized"));
    }

    /*
     * [REJECT] len(cells_present_bitmap) == len(header.kzg_commitments)
     */
    final int bitmapSize = sidecar.getCellsPresentBitmap().size();
    final int commitmentCount = header.getKzgCommitments().size();
    if (bitmapSize != commitmentCount) {
      return completedFuture(
          reject(
              "cells_present_bitmap length "
                  + bitmapSize
                  + " does not match header kzg_commitments length "
                  + commitmentCount));
    }

    /*
     * [REJECT] verify_partial_data_column_sidecar_kzg_proofs
     */
    final PartialDataColumnSidecarUtil util = spec.getPartialDataColumnSidecarUtil(slot);
    final List<KZGCommitment> commitments =
        header.getKzgCommitments().stream().map(SszKZGCommitment::getKZGCommitment).toList();
    if (!util.verifyKzgProofs(sidecar, commitments, columnIndex)) {
      return completedFuture(reject("PartialDataColumnSidecar KZG proof verification failed"));
    }

    return completedFuture(accept());
  }

  @SuppressWarnings("FormatStringAnnotation")
  private InternalValidationResult reject(final String reason) {
    LOG.trace("PartialDataColumnSidecar Gossip Validation: REJECT — {}", reason);
    return InternalValidationResult.reject(reason);
  }

  @SuppressWarnings("FormatStringAnnotation")
  private InternalValidationResult ignore(final String reason) {
    LOG.trace("PartialDataColumnSidecar Gossip Validation: IGNORE — {}", reason);
    return InternalValidationResult.ignore(reason);
  }

  private InternalValidationResult accept() {
    LOG.trace("PartialDataColumnSidecar Gossip Validation: ACCEPT");
    return InternalValidationResult.ACCEPT;
  }
}

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
import static tech.pegasys.teku.statetransition.validation.InternalValidationResult.SAVE_FOR_FUTURE;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.collections.LimitedSet;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.constants.Domain;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlockHeader;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlockHeader;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.logic.common.statetransition.results.BlockImportResult;
import tech.pegasys.teku.spec.logic.common.util.PartialDataColumnSidecarUtil;

/**
 * Validates the header portion of a partial data column message per §8.1 of the partial-messages
 * design doc. Should be called inside the async runner from {@code
 * PartialDataColumnSidecarHandler.onIncomingRpc} when the received partial message carries a {@link
 * PartialDataColumnHeaderFulu}.
 *
 * <p>On successful validation the validated header is stored in the supplied {@code
 * validatedHeaders} map so it can be reused for the cell validator and for subsequent partial
 * messages referencing the same block root.
 */
public class PartialDataColumnHeaderGossipValidator {

  private static final Logger LOG = LogManager.getLogger();
  private static final int VALID_SIGNED_BLOCK_HEADERS_SIZE = 500;

  private final Spec spec;
  private final GossipValidationHelper gossipValidationHelper;
  private final Map<Bytes32, BlockImportResult> invalidBlockRoots;
  private final Map<Bytes32, PartialDataColumnHeaderFulu> validatedHeaders;
  private final Set<Bytes32> validSignedBlockHeaders;

  public static PartialDataColumnHeaderGossipValidator create(
      final Spec spec,
      final Map<Bytes32, BlockImportResult> invalidBlockRoots,
      final GossipValidationHelper gossipValidationHelper,
      final Map<Bytes32, PartialDataColumnHeaderFulu> validatedHeaders) {
    return new PartialDataColumnHeaderGossipValidator(
        spec,
        invalidBlockRoots,
        gossipValidationHelper,
        validatedHeaders,
        LimitedSet.createSynchronized(VALID_SIGNED_BLOCK_HEADERS_SIZE));
  }

  PartialDataColumnHeaderGossipValidator(
      final Spec spec,
      final Map<Bytes32, BlockImportResult> invalidBlockRoots,
      final GossipValidationHelper gossipValidationHelper,
      final Map<Bytes32, PartialDataColumnHeaderFulu> validatedHeaders,
      final Set<Bytes32> validSignedBlockHeaders) {
    this.spec = spec;
    this.gossipValidationHelper = gossipValidationHelper;
    this.invalidBlockRoots = invalidBlockRoots;
    this.validatedHeaders = validatedHeaders;
    this.validSignedBlockHeaders = validSignedBlockHeaders;
  }

  /**
   * Validates a partial sidecar message that contains a {@link PartialDataColumnHeaderFulu}.
   *
   * @param sidecar the partial sidecar received from the peer
   * @param blockRoot the block root extracted from the group ID (version byte stripped)
   * @return ACCEPT, IGNORE, SAVE_FOR_FUTURE, or REJECT wrapped in a SafeFuture
   */
  public SafeFuture<InternalValidationResult> validate(
      final PartialDataColumnSidecarFulu sidecar, final Bytes32 blockRoot) {

    /*
     * [REJECT] A header is present (not semantically empty).
     */
    final Optional<PartialDataColumnHeaderFulu> optionalHeader = sidecar.getOptionalHeader();
    if (optionalHeader.isEmpty()) {
      return completedFuture(reject("Partial message has no header"));
    }
    final PartialDataColumnHeaderFulu header = optionalHeader.get();
    final SignedBeaconBlockHeader signedBlockHeader = header.getSignedBlockHeader();
    final BeaconBlockHeader blockHeader = signedBlockHeader.getMessage();
    final UInt64 slot = blockHeader.getSlot();

    /*
     * [REJECT] len(partial_column) == len(kzg_proofs) == popcount(cells_present_bitmap)
     */
    final int popcount = sidecar.getCellsPresentBitmap().getBitCount();
    if (sidecar.getPartialColumn().size() != popcount
        || sidecar.getKzgProofs().size() != popcount) {
      return completedFuture(
          reject(
              "Partial message cell/proof counts mismatch: partial_column="
                  + sidecar.getPartialColumn().size()
                  + " kzg_proofs="
                  + sidecar.getKzgProofs().size()
                  + " popcount(bitmap)="
                  + popcount));
    }

    /*
     * [REJECT] hash_tree_root(signed_block_header.message) == blockRoot
     */
    if (!blockHeader.hashTreeRoot().equals(blockRoot)) {
      return completedFuture(
          reject(
              "Block root from group ID does not match hash_tree_root of signed block header message"));
    }

    /*
     * [REJECT] kzg_commitments non-empty
     */
    if (header.getKzgCommitments().isEmpty()) {
      return completedFuture(reject("PartialDataColumnHeader has empty kzg_commitments"));
    }

    /*
     * [REJECT] If a previously-valid header for blockRoot exists in cache, the received header
     * MUST equal it.
     */
    final PartialDataColumnHeaderFulu cachedHeader = validatedHeaders.get(blockRoot);
    if (cachedHeader != null && !cachedHeader.equals(header)) {
      return completedFuture(
          reject(
              "Received PartialDataColumnHeader for blockRoot "
                  + blockRoot
                  + " does not match previously-validated header"));
    }

    /*
     * [IGNORE] block_header.slot <= current_slot + MAXIMUM_GOSSIP_CLOCK_DISPARITY
     */
    if (gossipValidationHelper.isSlotFromFuture(slot)) {
      return completedFuture(ignore("Partial message header slot is from the future"));
    }

    /*
     * [IGNORE] block_header.slot > finalized_slot
     */
    if (gossipValidationHelper.isSlotFinalized(slot)) {
      return completedFuture(ignore("Partial message header slot has been finalized"));
    }

    /*
     * [IGNORE] Parent block (block_header.parent_root) has been seen.
     */
    final Bytes32 parentRoot = blockHeader.getParentRoot();
    if (!gossipValidationHelper.isBlockAvailable(parentRoot)) {
      LOG.trace("Partial message header's parent block not yet seen; saving for future");
      return completedFuture(SAVE_FOR_FUTURE);
    }

    /*
     * [REJECT] Parent block passes validation.
     */
    if (invalidBlockRoots.containsKey(parentRoot)) {
      return completedFuture(reject("Partial message header's parent block is invalid"));
    }

    /*
     * [REJECT] block_header.slot > parent.slot
     */
    final Optional<UInt64> maybeParentSlot = gossipValidationHelper.getSlotForBlockRoot(parentRoot);
    if (maybeParentSlot.isEmpty() || !slot.isGreaterThan(maybeParentSlot.get())) {
      return completedFuture(
          reject(
              "Partial message header slot "
                  + slot
                  + " is not greater than parent slot "
                  + maybeParentSlot.orElse(UInt64.ZERO)));
    }
    final UInt64 parentSlot = maybeParentSlot.get();

    /*
     * [REJECT] finalized_checkpoint is an ancestor of the header's block.
     */
    if (!gossipValidationHelper.currentFinalizedCheckpointIsAncestorOfBlock(slot, parentRoot)) {
      return completedFuture(
          reject("Finalized checkpoint is not an ancestor of partial message header block"));
    }

    /*
     * [REJECT] verify_partial_data_column_header_inclusion_proof(header)
     */
    final PartialDataColumnSidecarUtil util = spec.getPartialDataColumnSidecarUtil(slot);
    if (!util.verifyHeaderInclusionProof(header)) {
      return completedFuture(
          reject("PartialDataColumnHeader kzg_commitments inclusion proof is invalid"));
    }

    /*
     * [REJECT] Proposer signature valid / [IGNORE] if shuffling unavailable.
     */
    return gossipValidationHelper
        .getParentStateInBlockEpoch(parentSlot, parentRoot, slot)
        .thenApply(
            maybeState -> {
              if (maybeState.isEmpty()) {
                return ignore(
                    "State unavailable for partial message header proposer validation; ignoring");
              }
              final BeaconState state = maybeState.get();

              final Bytes32 signedBlockHeaderRoot = signedBlockHeader.hashTreeRoot();
              if (!validSignedBlockHeaders.contains(signedBlockHeaderRoot)) {
                final Bytes32 domain =
                    spec.getDomain(
                        Domain.BEACON_PROPOSER,
                        spec.getCurrentEpoch(state),
                        state.getFork(),
                        state.getGenesisValidatorsRoot());
                final Bytes signingRoot = spec.computeSigningRoot(blockHeader, domain);
                if (!gossipValidationHelper.isSignatureValidWithRespectToProposerIndex(
                    signingRoot,
                    blockHeader.getProposerIndex(),
                    signedBlockHeader.getSignature(),
                    state)) {
                  return reject(
                      "PartialDataColumnHeader proposer signature is invalid for proposer "
                          + blockHeader.getProposerIndex());
                }
              }

              /*
               * [REJECT] Proposer is the expected proposer / [IGNORE] if shuffling unavailable.
               */
              if (!gossipValidationHelper.isProposerTheExpectedProposer(
                  blockHeader.getProposerIndex(), slot, state)) {
                return reject(
                    "PartialDataColumnHeader proposer "
                        + blockHeader.getProposerIndex()
                        + " is not the expected proposer for slot "
                        + slot);
              }

              validSignedBlockHeaders.add(signedBlockHeaderRoot);
              validatedHeaders.put(blockRoot, header);
              return accept();
            });
  }

  @SuppressWarnings("FormatStringAnnotation")
  private InternalValidationResult reject(final String reason) {
    LOG.trace("PartialDataColumnHeader Gossip Validation: REJECT — {}", reason);
    return InternalValidationResult.reject(reason);
  }

  @SuppressWarnings("FormatStringAnnotation")
  private InternalValidationResult ignore(final String reason) {
    LOG.trace("PartialDataColumnHeader Gossip Validation: IGNORE — {}", reason);
    return InternalValidationResult.ignore(reason);
  }

  private InternalValidationResult accept() {
    LOG.trace("PartialDataColumnHeader Gossip Validation: ACCEPT");
    return InternalValidationResult.ACCEPT;
  }
}

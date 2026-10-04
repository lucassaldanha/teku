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

package tech.pegasys.teku.spec.logic.common.block;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tech.pegasys.teku.spec.SpecMilestone.BELLATRIX;
import static tech.pegasys.teku.spec.SpecMilestone.CAPELLA;
import static tech.pegasys.teku.spec.SpecMilestone.DENEB;
import static tech.pegasys.teku.spec.SpecMilestone.ELECTRA;
import static tech.pegasys.teku.spec.SpecMilestone.FULU;
import static tech.pegasys.teku.spec.SpecMilestone.GLOAS;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import tech.pegasys.teku.bls.BLSSignatureVerifier;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecContext;
import tech.pegasys.teku.spec.TestSpecInvocationContextProvider.SpecContext;
import tech.pegasys.teku.spec.cache.IndexedAttestationCache;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadHeader;
import tech.pegasys.teku.spec.datastructures.execution.NewPayloadRequest;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.generator.ChainBuilder;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.InvalidBlockSignatureException;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.StateTransitionException;
import tech.pegasys.teku.spec.logic.versions.bellatrix.block.OptimisticExecutionPayloadExecutor;
import tech.pegasys.teku.spec.util.DataStructureUtil;

/**
 * The proposer signature must be verified before the block body is processed, so an unauthenticated
 * block never costs a state transition or an execution payload execution.
 */
@TestSpecContext(milestone = {BELLATRIX, CAPELLA, DENEB, ELECTRA, FULU, GLOAS})
class BlockProcessorProposerSignatureTest {

  private final RecordingPayloadExecutor payloadExecutor = new RecordingPayloadExecutor();

  private Spec spec;
  private SpecMilestone milestone;
  private DataStructureUtil dataStructureUtil;
  private BlockProcessor blockProcessor;
  private BeaconState blockSlotState;
  private SignedBeaconBlock validBlock;
  private SignedBeaconBlock invalidSignatureBlock;

  @BeforeEach
  void setUp(final SpecContext specContext) throws Exception {
    spec = specContext.getSpec();
    milestone = specContext.getSpecMilestone();
    dataStructureUtil = specContext.getDataStructureUtil();
    final ChainBuilder chainBuilder = ChainBuilder.create(spec);
    chainBuilder.generateGenesis();
    chainBuilder.generateBlocksUpToSlot(3);
    final SignedBlockAndState parent = chainBuilder.getLatestBlockAndState();
    final UInt64 blockSlot = parent.getSlot().plus(1);
    validBlock = chainBuilder.generateBlockAtSlot(blockSlot).getBlock();
    invalidSignatureBlock =
        SignedBeaconBlock.create(
            spec, validBlock.getMessage(), dataStructureUtil.randomSignature());
    blockSlotState = spec.processSlots(parent.getState(), blockSlot);
    blockProcessor = spec.getBlockProcessor(blockSlot);
  }

  @TestTemplate
  void shouldRejectInvalidProposerSignatureBeforeProcessingBlock() {
    assertThatThrownBy(
            () ->
                blockProcessor.processAndValidateBlock(
                    invalidSignatureBlock,
                    blockSlotState,
                    IndexedAttestationCache.NOOP,
                    Optional.of(payloadExecutor)))
        .isInstanceOf(InvalidBlockSignatureException.class)
        .hasMessageContaining("Invalid block signature");
    assertThat(payloadExecutor.invoked).isFalse();
  }

  @TestTemplate
  void shouldRejectWrongProposerIndexAsInvalidBlockNotAsInvalidSignature() {
    final BeaconBlock message = validBlock.getMessage();
    final BeaconBlock wrongProposerMessage =
        new BeaconBlock(
            message.getSchema(),
            message.getSlot(),
            message.getProposerIndex().plus(1),
            message.getParentRoot(),
            message.getStateRoot(),
            message.getBody());
    final SignedBeaconBlock wrongProposerBlock =
        SignedBeaconBlock.create(spec, wrongProposerMessage, validBlock.getSignature());

    assertThatThrownBy(
            () ->
                blockProcessor.processAndValidateBlock(
                    wrongProposerBlock,
                    blockSlotState,
                    IndexedAttestationCache.NOOP,
                    Optional.of(payloadExecutor)))
        .isInstanceOf(StateTransitionException.class)
        .isNotInstanceOf(InvalidBlockSignatureException.class)
        .hasMessageContaining("proposer index");
    assertThat(payloadExecutor.invoked).isFalse();
  }

  @TestTemplate
  void shouldProcessBlockWithValidProposerSignature() throws Exception {
    final BeaconState postState =
        blockProcessor.processAndValidateBlock(
            validBlock, blockSlotState, IndexedAttestationCache.NOOP, Optional.of(payloadExecutor));
    assertThat(postState.hashTreeRoot()).isEqualTo(validBlock.getStateRoot());
    // Execution only runs once the merge is complete, and from Gloas the payload is not part of
    // the block body at all.
    final boolean executionExpected =
        milestone.isLessThan(GLOAS) && spec.isMergeTransitionComplete(blockSlotState);
    assertThat(payloadExecutor.invoked.get()).isEqualTo(executionExpected);
  }

  @TestTemplate
  void shouldStillVerifyProposerSignatureWhenCallerSuppliesVerifier() {
    assertThatThrownBy(
            () ->
                blockProcessor.processAndValidateBlock(
                    invalidSignatureBlock,
                    blockSlotState,
                    IndexedAttestationCache.NOOP,
                    BLSSignatureVerifier.SIMPLE,
                    Optional.of(payloadExecutor)))
        .isInstanceOf(StateTransitionException.class)
        .isNotInstanceOf(InvalidBlockSignatureException.class)
        .hasMessageContaining("Invalid block signature");
  }

  private static class RecordingPayloadExecutor implements OptimisticExecutionPayloadExecutor {
    private final AtomicBoolean invoked = new AtomicBoolean(false);

    @Override
    public boolean optimisticallyExecute(
        final Optional<ExecutionPayloadHeader> latestExecutionPayloadHeader,
        final NewPayloadRequest payloadToExecute) {
      invoked.set(true);
      return true;
    }
  }
}

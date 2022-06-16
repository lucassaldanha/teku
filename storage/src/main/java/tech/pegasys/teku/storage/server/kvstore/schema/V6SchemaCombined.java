/*
 * Copyright ConsenSys Software Inc., 2022
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

package tech.pegasys.teku.storage.server.kvstore.schema;

import static tech.pegasys.teku.storage.server.kvstore.serialization.KvStoreSerializer.BYTES32_SERIALIZER;
import static tech.pegasys.teku.storage.server.kvstore.serialization.KvStoreSerializer.CHECKPOINT_EPOCHS_SERIALIZER;
import static tech.pegasys.teku.storage.server.kvstore.serialization.KvStoreSerializer.CHECKPOINT_SERIALIZER;
import static tech.pegasys.teku.storage.server.kvstore.serialization.KvStoreSerializer.DEPOSITS_FROM_BLOCK_EVENT_SERIALIZER;
import static tech.pegasys.teku.storage.server.kvstore.serialization.KvStoreSerializer.MIN_GENESIS_TIME_BLOCK_EVENT_SERIALIZER;
import static tech.pegasys.teku.storage.server.kvstore.serialization.KvStoreSerializer.SLOT_AND_BLOCK_ROOT_SERIALIZER;
import static tech.pegasys.teku.storage.server.kvstore.serialization.KvStoreSerializer.UINT64_SERIALIZER;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.ethereum.pow.api.DepositsFromBlockEvent;
import tech.pegasys.teku.ethereum.pow.api.MinGenesisTimeBlockEvent;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blocks.CheckpointEpochs;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.datastructures.forkchoice.VoteTracker;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.storage.server.kvstore.serialization.KvStoreSerializer;

public abstract class V6SchemaCombined implements SchemaCombined {

  public static final int V4_FINALIZED_OFFSET = 0;
  public static final int V6_FINALIZED_OFFSET = 128;

  // column ids should be distinct across different DAOs to make possible using
  // schemes both for a single and separated DBs
  protected final int finalizedOffset;

  private final KvStoreColumn<Bytes32, SignedBeaconBlock> hotBlocksByRoot;
  // Checkpoint states are no longer stored, keeping only for backwards compatibility.
  private final KvStoreColumn<Checkpoint, BeaconState> checkpointStates;
  private final KvStoreColumn<UInt64, VoteTracker> votes;
  private static final KvStoreColumn<UInt64, DepositsFromBlockEvent> DEPOSITS_FROM_BLOCK_EVENTS =
      KvStoreColumn.create(4, UINT64_SERIALIZER, DEPOSITS_FROM_BLOCK_EVENT_SERIALIZER);
  private static final KvStoreColumn<Bytes32, SlotAndBlockRoot> STATE_ROOT_TO_SLOT_AND_BLOCK_ROOT =
      KvStoreColumn.create(5, BYTES32_SERIALIZER, SLOT_AND_BLOCK_ROOT_SERIALIZER);
  private final KvStoreColumn<Bytes32, BeaconState> hotStatesByRoot;
  private static final KvStoreColumn<Bytes32, CheckpointEpochs>
      HOT_BLOCK_CHECKPOINT_EPOCHS_BY_ROOT =
          KvStoreColumn.create(7, BYTES32_SERIALIZER, CHECKPOINT_EPOCHS_SERIALIZER);

  // Variables
  private static final KvStoreVariable<UInt64> GENESIS_TIME =
      KvStoreVariable.create(1, UINT64_SERIALIZER);
  private static final KvStoreVariable<Checkpoint> JUSTIFIED_CHECKPOINT =
      KvStoreVariable.create(2, CHECKPOINT_SERIALIZER);
  private static final KvStoreVariable<Checkpoint> BEST_JUSTIFIED_CHECKPOINT =
      KvStoreVariable.create(3, CHECKPOINT_SERIALIZER);
  private static final KvStoreVariable<Checkpoint> FINALIZED_CHECKPOINT =
      KvStoreVariable.create(4, CHECKPOINT_SERIALIZER);
  private final KvStoreVariable<BeaconState> latestFinalizedState;
  private static final KvStoreVariable<MinGenesisTimeBlockEvent> MIN_GENESIS_TIME_BLOCK =
      KvStoreVariable.create(6, MIN_GENESIS_TIME_BLOCK_EVENT_SERIALIZER);
  // 7 was the protoarray snapshot variable but is no longer used.
  private static final KvStoreVariable<Checkpoint> WEAK_SUBJECTIVITY_CHECKPOINT =
      KvStoreVariable.create(8, CHECKPOINT_SERIALIZER);
  private static final KvStoreVariable<Checkpoint> ANCHOR_CHECKPOINT =
      KvStoreVariable.create(9, CHECKPOINT_SERIALIZER);

  private final KvStoreVariable<UInt64> optimisticTransitionBlockSlot;

  protected V6SchemaCombined(
      final Spec spec, final boolean storeVotesEquivocation, final int finalizedOffset) {
    this.finalizedOffset = finalizedOffset;
    final KvStoreSerializer<SignedBeaconBlock> signedBlockSerializer =
        KvStoreSerializer.createSignedBlockSerializer(spec);
    hotBlocksByRoot = KvStoreColumn.create(1, BYTES32_SERIALIZER, signedBlockSerializer);

    final KvStoreSerializer<BeaconState> stateSerializer =
        KvStoreSerializer.createStateSerializer(spec);
    checkpointStates = KvStoreColumn.create(2, CHECKPOINT_SERIALIZER, stateSerializer);
    hotStatesByRoot = KvStoreColumn.create(6, BYTES32_SERIALIZER, stateSerializer);
    latestFinalizedState = KvStoreVariable.create(5, stateSerializer);
    final KvStoreSerializer<VoteTracker> voteTrackerSerializer =
        KvStoreSerializer.createVoteTrackerSerializer(storeVotesEquivocation);
    votes = KvStoreColumn.create(3, UINT64_SERIALIZER, voteTrackerSerializer);

    optimisticTransitionBlockSlot = KvStoreVariable.create(finalizedOffset + 1, UINT64_SERIALIZER);
  }

  @Override
  public KvStoreColumn<Bytes32, SignedBeaconBlock> getColumnHotBlocksByRoot() {
    return hotBlocksByRoot;
  }

  @Override
  public KvStoreColumn<Bytes32, CheckpointEpochs> getColumnHotBlockCheckpointEpochsByRoot() {
    return HOT_BLOCK_CHECKPOINT_EPOCHS_BY_ROOT;
  }

  @Override
  public KvStoreColumn<Checkpoint, BeaconState> getColumnCheckpointStates() {
    return checkpointStates;
  }

  @Override
  public KvStoreColumn<UInt64, VoteTracker> getColumnVotes() {
    return votes;
  }

  @Override
  public KvStoreColumn<UInt64, DepositsFromBlockEvent> getColumnDepositsFromBlockEvents() {
    return DEPOSITS_FROM_BLOCK_EVENTS;
  }

  @Override
  public KvStoreColumn<Bytes32, SlotAndBlockRoot> getColumnStateRootToSlotAndBlockRoot() {
    return STATE_ROOT_TO_SLOT_AND_BLOCK_ROOT;
  }

  @Override
  public KvStoreColumn<Bytes32, BeaconState> getColumnHotStatesByRoot() {
    return hotStatesByRoot;
  }

  @Override
  public KvStoreVariable<UInt64> getVariableGenesisTime() {
    return GENESIS_TIME;
  }

  @Override
  public KvStoreVariable<Checkpoint> getVariableJustifiedCheckpoint() {
    return JUSTIFIED_CHECKPOINT;
  }

  @Override
  public KvStoreVariable<Checkpoint> getVariableBestJustifiedCheckpoint() {
    return BEST_JUSTIFIED_CHECKPOINT;
  }

  @Override
  public KvStoreVariable<Checkpoint> getVariableFinalizedCheckpoint() {
    return FINALIZED_CHECKPOINT;
  }

  @Override
  public KvStoreVariable<BeaconState> getVariableLatestFinalizedState() {
    return latestFinalizedState;
  }

  @Override
  public KvStoreVariable<MinGenesisTimeBlockEvent> getVariableMinGenesisTimeBlock() {
    return MIN_GENESIS_TIME_BLOCK;
  }

  @Override
  public KvStoreVariable<Checkpoint> getVariableWeakSubjectivityCheckpoint() {
    return WEAK_SUBJECTIVITY_CHECKPOINT;
  }

  @Override
  public KvStoreVariable<Checkpoint> getVariableAnchorCheckpoint() {
    return ANCHOR_CHECKPOINT;
  }

  @Override
  public KvStoreVariable<UInt64> getOptimisticTransitionBlockSlot() {
    return optimisticTransitionBlockSlot;
  }

  @Override
  public Map<String, KvStoreColumn<?, ?>> getColumnMap() {
    return ImmutableMap.<String, KvStoreColumn<?, ?>>builder()
        .put("HOT_BLOCKS_BY_ROOT", getColumnHotBlocksByRoot())
        .put("CHECKPOINT_STATES", getColumnCheckpointStates())
        .put("VOTES", getColumnVotes())
        .put("DEPOSITS_FROM_BLOCK_EVENTS", getColumnDepositsFromBlockEvents())
        .put("STATE_ROOT_TO_SLOT_AND_BLOCK_ROOT", getColumnStateRootToSlotAndBlockRoot())
        .put("HOT_STATES_BY_ROOT", getColumnHotStatesByRoot())
        .put("HOT_BLOCK_CHECKPOINT_EPOCHS_BY_ROOT", getColumnHotBlockCheckpointEpochsByRoot())
        .put("SLOTS_BY_FINALIZED_ROOT", getColumnSlotsByFinalizedRoot())
        .put("FINALIZED_BLOCKS_BY_SLOT", getColumnFinalizedBlocksBySlot())
        .put("SLOTS_BY_FINALIZED_STATE_ROOT", getColumnSlotsByFinalizedStateRoot())
        .put("NON_CANONICAL_BLOCKS_BY_ROOT", getColumnNonCanonicalBlocksByRoot())
        .put("NON_CANONICAL_BLOCK_ROOTS_BY_SLOT", getColumnNonCanonicalRootsBySlot())
        .build();
  }

  @Override
  public Map<String, KvStoreVariable<?>> getVariableMap() {
    return Map.of(
        "GENESIS_TIME", getVariableGenesisTime(),
        "JUSTIFIED_CHECKPOINT", getVariableJustifiedCheckpoint(),
        "BEST_JUSTIFIED_CHECKPOINT", getVariableBestJustifiedCheckpoint(),
        "FINALIZED_CHECKPOINT", getVariableFinalizedCheckpoint(),
        "LATEST_FINALIZED_STATE", getVariableLatestFinalizedState(),
        "MIN_GENESIS_TIME_BLOCK", getVariableMinGenesisTimeBlock(),
        "WEAK_SUBJECTIVITY_CHECKPOINT", getVariableWeakSubjectivityCheckpoint(),
        "ANCHOR_CHECKPOINT", getVariableAnchorCheckpoint(),
        "OPTIMISTIC_TRANSITION_BLOCK_SLOT", getOptimisticTransitionBlockSlot());
  }
}
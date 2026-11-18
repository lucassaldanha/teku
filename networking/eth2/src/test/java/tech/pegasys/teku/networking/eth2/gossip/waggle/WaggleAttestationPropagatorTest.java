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

package tech.pegasys.teku.networking.eth2.gossip.waggle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.async.StubAsyncRunner;
import tech.pegasys.teku.infrastructure.metrics.StubMetricsSystem;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.time.StubTimeProvider;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.gossip.waggle.AttestationBundle.AttestationBundleSchema;
import tech.pegasys.teku.networking.eth2.gossip.waggle.CommitteeAttestationPartsMetadata.CommitteeAttestationPartsMetadataSchema;
import tech.pegasys.teku.networking.eth2.gossip.waggle.WaggleAttestationPropagator.Config;
import tech.pegasys.teku.networking.p2p.mock.MockNodeId;
import tech.pegasys.teku.networking.p2p.peer.NodeId;
import tech.pegasys.teku.networking.p2p.waggle.WaggleNetwork;
import tech.pegasys.teku.networking.p2p.waggle.WaggleTopicHandler;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.operations.AttestationData;
import tech.pegasys.teku.spec.datastructures.operations.SingleAttestation;
import tech.pegasys.teku.spec.datastructures.operations.SingleAttestationSchema;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;

class WaggleAttestationPropagatorTest {
  private static final int SUBNET = 3;
  private static final UInt64 CURRENT_SLOT = UInt64.valueOf(10);
  private static final long MAX_VALIDATORS_PER_COMMITTEE = 2048;

  private final Spec spec = TestSpecFactory.createMinimalElectra();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final SingleAttestationSchema attestationSchema =
      spec.getGenesisSchemaDefinitions()
          .toVersionElectra()
          .orElseThrow()
          .getSingleAttestationSchema();
  private final AttestationBundleSchema bundleSchema =
      new AttestationBundleSchema(MAX_VALIDATORS_PER_COMMITTEE);
  private final CommitteeAttestationPartsMetadataSchema metadataSchema =
      new CommitteeAttestationPartsMetadataSchema(MAX_VALIDATORS_PER_COMMITTEE);
  private final StubAsyncRunner asyncRunner = new StubAsyncRunner();
  private final StubTimeProvider timeProvider = StubTimeProvider.withTimeInMillis(1_000_000);
  private final Hub hub = new Hub();

  private record Frame(
      NodeId from, NodeId to, Bytes objectId, Optional<Bytes> metadata, Optional<Bytes> pieces) {}

  /** An in-memory Waggle: frames go straight to the handler of a subscribed receiver. */
  private static class Hub {
    private final Map<NodeId, Map<String, WaggleTopicHandler>> handlers = new HashMap<>();
    private final List<Frame> frames = new ArrayList<>();

    WaggleNetwork networkOf(final NodeId self) {
      return new WaggleNetwork() {
        @Override
        public SafeFuture<Void> subscribe(final String topic, final WaggleTopicHandler handler) {
          handlers.computeIfAbsent(self, __ -> new HashMap<>()).put(topic, handler);
          return SafeFuture.COMPLETE;
        }

        @Override
        public SafeFuture<Void> unsubscribe(final String topic) {
          handlers.getOrDefault(self, new HashMap<>()).remove(topic);
          return SafeFuture.COMPLETE;
        }

        @Override
        public SafeFuture<Void> send(
            final String topic,
            final NodeId peer,
            final Bytes objectId,
            final Optional<Bytes> metadata,
            final Optional<Bytes> pieces) {
          final WaggleTopicHandler handler = handlers.getOrDefault(peer, Map.of()).get(topic);
          if (handler == null) {
            return SafeFuture.failedFuture(new IllegalStateException("peer is not subscribed"));
          }
          frames.add(new Frame(self, peer, objectId, metadata, pieces));
          handler.onMessage(self, objectId, metadata, pieces);
          return SafeFuture.COMPLETE;
        }

        @Override
        public SafeFuture<Collection<NodeId>> getSubscribers(final String topic) {
          return SafeFuture.completedFuture(
              handlers.entrySet().stream()
                  .filter(
                      entry -> !entry.getKey().equals(self) && entry.getValue().containsKey(topic))
                  .map(Map.Entry::getKey)
                  .toList());
        }
      };
    }
  }

  private class Node {
    private final NodeId id;
    private final StubMetricsSystem metrics = new StubMetricsSystem();
    private final List<SingleAttestation> validated = new ArrayList<>();
    private final List<SingleAttestation> publishedOnGossip = new ArrayList<>();
    private InternalValidationResult validationResult = InternalValidationResult.ACCEPT;
    private final WaggleAttestationPropagator propagator;

    private Node(final int id, final Config config) {
      this.id = new MockNodeId(id);
      this.propagator =
          new WaggleAttestationPropagator(
              asyncRunner,
              timeProvider,
              metrics,
              hub.networkOf(this.id),
              () -> Optional.of(CURRENT_SLOT),
              attestationSchema,
              MAX_VALIDATORS_PER_COMMITTEE,
              subnetId -> "/eth2/00000000/beacon_attestation_" + subnetId + "/ssz_snappy",
              (subnetId, attestation) -> {
                validated.add(attestation);
                return SafeFuture.completedFuture(validationResult);
              },
              (subnetId, attestation) -> publishedOnGossip.add(attestation),
              config,
              new Random(1));
    }

    private Node subscribed() {
      propagator.subscribe(SUBNET);
      return this;
    }

    private long events(final String event) {
      return metrics.getLabelledCounterValue(
          TekuMetricCategory.NETWORK, "waggle_attestation_events_total", event);
    }
  }

  private static Config pushOnly() {
    return new Config(
        8,
        Duration.ofMillis(20),
        0,
        Duration.ofMillis(100),
        2,
        Duration.ofMillis(500),
        Duration.ofSeconds(3),
        4);
  }

  private static Config advertiseOnly() {
    return new Config(
        0,
        Duration.ofMillis(20),
        2,
        Duration.ofMillis(100),
        2,
        Duration.ofMillis(500),
        Duration.ofSeconds(3),
        4);
  }

  private SingleAttestation attestation(final AttestationData data, final int validator) {
    return attestationSchema.create(
        UInt64.valueOf(1), UInt64.valueOf(validator), data, dataStructureUtil.randomSignature());
  }

  private AttestationData dataAt(final UInt64 slot) {
    return dataStructureUtil.randomAttestationData(slot);
  }

  private static void deliver(
      final WaggleNetwork from,
      final String topic,
      final NodeId to,
      final Bytes objectId,
      final Optional<Bytes> metadata,
      final Optional<Bytes> pieces) {
    assertThat(from.send(topic, to, objectId, metadata, pieces)).isCompleted();
  }

  /** Runs the scheduled push and advertise ticks until nothing is left to do. */
  private void settle() {
    for (int i = 0; i < 50 && asyncRunner.hasDelayedActions(); i++) {
      asyncRunner.executeQueuedActions();
    }
  }

  @Test
  void pushesAValidatedVoteWhichTheReceiverValidatesStoresAndForwards() {
    final Node a = new Node(1, pushOnly()).subscribed();
    final Node b = new Node(2, pushOnly()).subscribed();
    final Node c = new Node(3, pushOnly()).subscribed();
    final SingleAttestation attestation = attestation(dataAt(CURRENT_SLOT), 7);

    a.propagator.submit(SUBNET, attestation);
    settle();

    assertThat(b.validated).containsExactly(attestation);
    assertThat(c.validated).containsExactly(attestation);
    // Valid attestations from Waggle are bridged to gossipsub; our own submission is not.
    assertThat(b.publishedOnGossip).containsExactly(attestation);
    assertThat(a.publishedOnGossip).isEmpty();
    // Nobody is sent a vote it is known to have: nothing goes back to a, and a pushes once to
    // each. b and c cannot know the other already has it, so they may push to each other; the
    // duplicate is dropped without a second validation (asserted above).
    assertThat(hub.frames).noneMatch(frame -> frame.to().equals(a.id));
    assertThat(hub.frames.stream().filter(frame -> frame.from().equals(a.id))).hasSize(2);
  }

  @Test
  void usesTheSlotAsObjectIdAndBundlesVotesForTheSameData() {
    final Node a = new Node(1, pushOnly()).subscribed();
    final Node b = new Node(2, pushOnly()).subscribed();
    final AttestationData data = dataAt(CURRENT_SLOT);

    IntStream.range(0, 3).forEach(i -> a.propagator.submit(SUBNET, attestation(data, i)));
    settle();

    assertThat(hub.frames).hasSize(1);
    final Frame frame = hub.frames.get(0);
    assertThat(frame.objectId()).isEqualTo(Bytes.fromHexString("0x0a00000000000000"));
    assertThat(frame.metadata()).isEmpty();
    final AttestationBundle bundle = bundleSchema.sszDeserialize(frame.pieces().orElseThrow());
    assertThat(bundle.getData()).isEqualTo(data);
    assertThat(bundle.getCommitteeIndex()).isEqualTo(UInt64.valueOf(1));
    assertThat(bundle.getAttesterIndices())
        .containsExactly(UInt64.valueOf(0), UInt64.valueOf(1), UInt64.valueOf(2));
    assertThat(b.validated).hasSize(3);
  }

  @Test
  void splitsBundlesAtTheMaximumSize() {
    final Node a = new Node(1, pushOnly()).subscribed();
    final Node b = new Node(2, pushOnly()).subscribed();
    final AttestationData data = dataAt(CURRENT_SLOT);

    IntStream.range(0, 120).forEach(i -> a.propagator.submit(SUBNET, attestation(data, i)));
    settle();

    assertThat(
            hub.frames.stream()
                .map(
                    f ->
                        bundleSchema
                            .sszDeserialize(f.pieces().orElseThrow())
                            .getAttesterIndices()
                            .size()))
        .containsExactly(50, 50, 20);
    assertThat(b.validated).hasSize(120);
  }

  @Test
  void publishesToASubnetItDoesNotFollow() {
    final Node a = new Node(1, pushOnly()); // not subscribed
    final Node b = new Node(2, pushOnly()).subscribed();
    final SingleAttestation attestation = attestation(dataAt(CURRENT_SLOT), 7);

    a.propagator.submit(SUBNET, attestation);
    settle();

    assertThat(b.validated).containsExactly(attestation);
  }

  @Test
  void advertisedVotesAreRequestedAndServed() {
    final Node a = new Node(1, advertiseOnly()).subscribed();
    final Node b = new Node(2, advertiseOnly()).subscribed();
    final AttestationData data = dataAt(CURRENT_SLOT);
    final SingleAttestation fromA = attestation(data, 1);
    final SingleAttestation fromB = attestation(data, 2);

    // Both hold a vote for the data, so each knows the data root the other advertises.
    a.propagator.submit(SUBNET, fromA);
    b.propagator.submit(SUBNET, fromB);
    settle();

    assertThat(b.validated).containsExactly(fromA);
    assertThat(a.validated).containsExactly(fromB);
    // available -> request -> bundle, in each direction.
    final List<Frame> toB = hub.frames.stream().filter(frame -> frame.to().equals(b.id)).toList();
    final CommitteeAttestationPartsMetadata available =
        metadataSchema.sszDeserialize(toB.get(0).metadata().orElseThrow());
    assertThat(available.getDataRoot()).isEqualTo(data.hashTreeRoot());
    assertThat(available.getAvailable()).containsExactly(UInt64.valueOf(1));
    assertThat(available.getRequests()).isEmpty();
    assertThat(a.events("request_served")).isEqualTo(1);
    assertThat(b.events("request_sent")).isEqualTo(1);
    // Once both have both votes nothing more is advertised.
    final int frames = hub.frames.size();
    timeProvider.advanceTimeByMillis(200);
    settle();
    assertThat(hub.frames).hasSize(frames);
  }

  @Test
  void listsForAnUnknownDataRootAreDropped() {
    final Node a = new Node(1, advertiseOnly()).subscribed();
    final Node b = new Node(2, advertiseOnly()).subscribed();

    a.propagator.submit(SUBNET, attestation(dataAt(CURRENT_SLOT), 1));
    settle();

    assertThat(b.events("metadata_unknown_root")).isEqualTo(1);
    assertThat(b.validated).isEmpty();
    assertThat(b.events("request_sent")).isZero();
  }

  @Test
  void aRejectedAttestationIsNotStoredForwardedOrValidatedTwice() {
    final Node a = new Node(1, pushOnly()).subscribed();
    final Node b = new Node(2, pushOnly()).subscribed();
    final Node c = new Node(3, pushOnly()).subscribed();
    b.validationResult = InternalValidationResult.reject("bad signature");
    c.validationResult = InternalValidationResult.reject("bad signature");
    final AttestationData data = dataAt(CURRENT_SLOT);
    final SingleAttestation bad = attestation(data, 7);
    final Bytes bundle =
        bundleSchema
            .create(
                UInt64.valueOf(1), data, List.of(UInt64.valueOf(7)), List.of(bad.getSignature()))
            .sszSerialize();
    final WaggleNetwork fromA = hub.networkOf(a.id);
    final String topic = "/eth2/00000000/beacon_attestation_" + SUBNET + "/ssz_snappy";

    deliver(
        fromA,
        topic,
        b.id,
        WaggleAttestationPropagator.encodeSlot(CURRENT_SLOT),
        Optional.empty(),
        Optional.of(bundle));
    deliver(
        fromA,
        topic,
        b.id,
        WaggleAttestationPropagator.encodeSlot(CURRENT_SLOT),
        Optional.empty(),
        Optional.of(bundle));
    settle();

    assertThat(b.validated).hasSize(1);
    assertThat(b.publishedOnGossip).isEmpty();
    assertThat(b.events("attestation_invalid")).isEqualTo(1);
    assertThat(hub.frames).noneMatch(frame -> frame.from().equals(b.id));

    // A different signature for the same vote is a different attempt and is validated.
    b.validationResult = InternalValidationResult.ACCEPT;
    final SingleAttestation good = attestation(data, 7);
    a.propagator.submit(SUBNET, good);
    settle();
    assertThat(b.validated).containsExactly(bad, good);
    assertThat(b.publishedOnGossip).containsExactly(good);
  }

  @Test
  void anIgnoredAttestationCanBeValidatedAgainLater() {
    final Node a = new Node(1, pushOnly()).subscribed();
    final Node b = new Node(2, pushOnly()).subscribed();
    b.validationResult = InternalValidationResult.IGNORE;
    final AttestationData data = dataAt(CURRENT_SLOT);
    final SingleAttestation attestation = attestation(data, 7);
    final Bytes bundle =
        bundleSchema
            .create(
                UInt64.valueOf(1),
                data,
                List.of(UInt64.valueOf(7)),
                List.of(attestation.getSignature()))
            .sszSerialize();
    final String topic = "/eth2/00000000/beacon_attestation_" + SUBNET + "/ssz_snappy";
    final Bytes slot = WaggleAttestationPropagator.encodeSlot(CURRENT_SLOT);

    deliver(hub.networkOf(a.id), topic, b.id, slot, Optional.empty(), Optional.of(bundle));
    b.validationResult = InternalValidationResult.ACCEPT;
    deliver(hub.networkOf(a.id), topic, b.id, slot, Optional.empty(), Optional.of(bundle));

    assertThat(b.validated).hasSize(2);
    assertThat(b.publishedOnGossip).hasSize(1);
  }

  @Test
  void malformedAndOutOfWindowFramesAreDropped() {
    final Node a = new Node(1, pushOnly()).subscribed();
    final Node b = new Node(2, pushOnly()).subscribed();
    final WaggleNetwork fromA = hub.networkOf(a.id);
    final String topic = "/eth2/00000000/beacon_attestation_" + SUBNET + "/ssz_snappy";
    final AttestationData data = dataAt(CURRENT_SLOT);
    final SingleAttestation attestation = attestation(data, 7);
    final Bytes slot = WaggleAttestationPropagator.encodeSlot(CURRENT_SLOT);

    // The same validator twice.
    final Bytes duplicated =
        bundleSchema
            .create(
                UInt64.valueOf(1),
                data,
                List.of(UInt64.valueOf(7), UInt64.valueOf(7)),
                List.of(attestation.getSignature(), attestation.getSignature()))
            .sszSerialize();
    deliver(fromA, topic, b.id, slot, Optional.empty(), Optional.of(duplicated));
    // A bundle whose data is for another slot than the object.
    final Bytes wrongSlot =
        bundleSchema
            .create(
                UInt64.valueOf(1),
                dataAt(CURRENT_SLOT.minus(1)),
                List.of(UInt64.valueOf(7)),
                List.of(attestation.getSignature()))
            .sszSerialize();
    deliver(fromA, topic, b.id, slot, Optional.empty(), Optional.of(wrongSlot));
    // Garbage, and an object id that is not a slot.
    deliver(fromA, topic, b.id, slot, Optional.empty(), Optional.of(Bytes.fromHexString("0x0102")));
    deliver(fromA, topic, b.id, Bytes.fromHexString("0x01"), Optional.empty(), Optional.empty());
    assertThat(b.events("frame_invalid")).isEqualTo(4);

    // Too old and too far ahead.
    final AttestationData old = dataAt(CURRENT_SLOT.minus(5));
    deliver(
        fromA,
        topic,
        b.id,
        WaggleAttestationPropagator.encodeSlot(old.getSlot()),
        Optional.empty(),
        Optional.of(
            bundleSchema
                .create(
                    UInt64.valueOf(1),
                    old,
                    List.of(UInt64.valueOf(7)),
                    List.of(attestation.getSignature()))
                .sszSerialize()));
    deliver(
        fromA,
        topic,
        b.id,
        WaggleAttestationPropagator.encodeSlot(CURRENT_SLOT.plus(2)),
        Optional.empty(),
        Optional.empty());
    assertThat(b.events("frame_outside_window")).isEqualTo(2);

    assertThat(b.validated).isEmpty();
  }

  @Test
  void keepsAtMostTwoVotesOfAValidatorInASlot() {
    final Node a = new Node(1, pushOnly()).subscribed();
    final Node b = new Node(2, pushOnly()).subscribed();

    // Three different votes of validator 7 in one slot: the third is not propagated.
    IntStream.range(0, 3)
        .forEach(i -> a.propagator.submit(SUBNET, attestation(dataAt(CURRENT_SLOT), 7)));
    settle();

    assertThat(b.validated).hasSize(2);
  }
}

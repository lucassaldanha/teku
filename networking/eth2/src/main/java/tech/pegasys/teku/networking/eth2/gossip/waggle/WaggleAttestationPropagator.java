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

import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.time.TimeProvider;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.gossip.waggle.AttestationBundle.AttestationBundleSchema;
import tech.pegasys.teku.networking.eth2.gossip.waggle.CommitteeAttestationPartsMetadata.CommitteeAttestationPartsMetadataSchema;
import tech.pegasys.teku.networking.p2p.peer.NodeId;
import tech.pegasys.teku.networking.p2p.waggle.WaggleNetwork;
import tech.pegasys.teku.spec.datastructures.operations.AttestationData;
import tech.pegasys.teku.spec.datastructures.operations.SingleAttestation;
import tech.pegasys.teku.spec.datastructures.operations.SingleAttestationSchema;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;

/**
 * Prototype of EIP-8334 (draft, "Bundled Attestation Propagation"): propagates the attestations of
 * the attestation subnets of one fork over Waggle, next to gossipsub.
 *
 * <p>Each subnet has a Waggle topic with the name of its gossipsub topic. The object of a frame is
 * a slot (8 bytes, little endian), its pieces an {@link AttestationBundle} and its metadata a
 * {@link CommitteeAttestationPartsMetadata}. An attestation ("vote") is identified by (attestation
 * data root, validator index). Three things happen:
 *
 * <ul>
 *   <li><b>Push:</b> a vote we validated goes, shortly after, to a few random subscribers that do
 *       not have it, bundled with the other new votes for the same data.
 *   <li><b>Advertise:</b> for a few seconds after a vote arrives, periodic metadata to a few random
 *       subscribers lists it as available, unless the peer is known to have it.
 *   <li><b>Request and serve:</b> a node that sees an available vote it lacks requests it, and the
 *       holder answers with a bundle.
 * </ul>
 *
 * <p>Only validated votes are stored, pushed, advertised or served. Votes received over Waggle go
 * through the regular gossip validation first and, when valid, are also published on gossipsub;
 * votes validated from gossipsub and our own are handed to {@link #submit}. That bridging is what
 * keeps peers without Waggle working.
 *
 * <p>The wire format follows Prysm (sukunrt/prysm, branch eip-8334-waggle), which is the only other
 * implementation.
 */
public class WaggleAttestationPropagator {

  private static final Logger LOG = LogManager.getLogger();

  /** Votes of one validator we keep per slot: two different ones are enough for a slashing. */
  private static final int MAX_VOTES_PER_VALIDATOR = 2;

  /**
   * @param pushPeers peers that get each new vote (ATTESTATION_PUSH_FACTOR)
   * @param pushInterval how long new votes are collected before a push
   *     (ATTESTATION_BUNDLE_TICK_INTERVAL)
   * @param metadataPeers peers that get our available lists on each advertise tick
   * @param metadataInterval time between advertise ticks
   * @param requestPeers open requests allowed for one vote
   * @param requestTimeout after this long a request no longer counts as open
   * @param advertWindow how long after its arrival a vote is advertised
   * @param retainedSlots slots behind the current one for which votes are kept and accepted
   */
  public record Config(
      int pushPeers,
      Duration pushInterval,
      int metadataPeers,
      Duration metadataInterval,
      int requestPeers,
      Duration requestTimeout,
      Duration advertWindow,
      int retainedSlots) {

    public static final Config DEFAULT =
        new Config(
            8,
            Duration.ofMillis(20),
            2,
            Duration.ofMillis(100),
            2,
            Duration.ofMillis(500),
            Duration.ofSeconds(3),
            4);
  }

  /** Runs one attestation received over Waggle through the gossip validation of its subnet. */
  @FunctionalInterface
  public interface AttestationValidator {
    SafeFuture<InternalValidationResult> validate(int subnetId, SingleAttestation attestation);
  }

  /** Publishes an attestation that arrived over Waggle and passed validation on gossipsub. */
  @FunctionalInterface
  public interface GossipPublisher {
    void publish(int subnetId, SingleAttestation attestation);
  }

  private record Vote(Bytes32 dataRoot, UInt64 validator) {}

  private record StoredVote(BLSSignature signature, UInt64 arrivalMillis) {}

  private record RootInfo(UInt64 committeeIndex, AttestationData data) {}

  private record Attempt(Vote vote, BLSSignature signature) {}

  /** What we know about one peer for one slot: the votes it has, or has been told about by us. */
  private static class PeerView {
    private final Set<Vote> known = new HashSet<>();
  }

  private static class SlotState {
    private final Map<Vote, StoredVote> votes = new LinkedHashMap<>();
    private final Map<Bytes32, RootInfo> roots = new HashMap<>();
    private final Map<UInt64, Integer> votesPerValidator = new HashMap<>();
    private final Map<NodeId, PeerView> peers = new HashMap<>();
    private final Set<Vote> pendingPush = new LinkedHashSet<>();

    /** Per wanted vote: whom we asked, and when. */
    private final Map<Vote, Map<NodeId, UInt64>> asks = new HashMap<>();

    /** Signatures in validation or rejected: never validated twice. */
    private final Set<Attempt> attempts = new HashSet<>();

    private UInt64 lastArrivalMillis = UInt64.ZERO;

    private PeerView peer(final NodeId peer) {
      return peers.computeIfAbsent(peer, __ -> new PeerView());
    }
  }

  private static class SubnetState {
    private final int subnetId;
    private final String topic;
    private boolean subscribed = false;
    private final TreeMap<UInt64, SlotState> slots = new TreeMap<>();

    private SubnetState(final int subnetId, final String topic) {
      this.subnetId = subnetId;
      this.topic = topic;
    }
  }

  private final AsyncRunner asyncRunner;
  private final TimeProvider timeProvider;
  private final WaggleNetwork waggleNetwork;
  private final Supplier<Optional<UInt64>> currentSlotSupplier;
  private final SingleAttestationSchema attestationSchema;
  private final AttestationBundleSchema bundleSchema;
  private final CommitteeAttestationPartsMetadataSchema metadataSchema;
  private final IntFunction<String> topicForSubnet;
  private final AttestationValidator validator;
  private final GossipPublisher gossipPublisher;
  private final Config config;
  private final Random random;
  private final LabelledMetric<Counter> eventCounter;

  private final Map<Integer, SubnetState> subnets = new HashMap<>();
  private boolean pushScheduled = false;
  private boolean advertiseScheduled = false;

  public WaggleAttestationPropagator(
      final AsyncRunner asyncRunner,
      final TimeProvider timeProvider,
      final MetricsSystem metricsSystem,
      final WaggleNetwork waggleNetwork,
      final Supplier<Optional<UInt64>> currentSlotSupplier,
      final SingleAttestationSchema attestationSchema,
      final long maxValidatorsPerCommittee,
      final IntFunction<String> topicForSubnet,
      final AttestationValidator validator,
      final GossipPublisher gossipPublisher,
      final Config config,
      final Random random) {
    this.asyncRunner = asyncRunner;
    this.timeProvider = timeProvider;
    this.waggleNetwork = waggleNetwork;
    this.currentSlotSupplier = currentSlotSupplier;
    this.attestationSchema = attestationSchema;
    this.bundleSchema = new AttestationBundleSchema(maxValidatorsPerCommittee);
    this.metadataSchema = new CommitteeAttestationPartsMetadataSchema(maxValidatorsPerCommittee);
    this.topicForSubnet = topicForSubnet;
    this.validator = validator;
    this.gossipPublisher = gossipPublisher;
    this.config = config;
    this.random = random;
    this.eventCounter =
        metricsSystem.createLabelledCounter(
            TekuMetricCategory.NETWORK,
            "waggle_attestation_events_total",
            "Events of attestation propagation over Waggle (EIP-8334 prototype)",
            "event");
  }

  // ---- Subscriptions --------------------------------------------------------------------------

  public synchronized void subscribe(final int subnetId) {
    final SubnetState subnet = subnet(subnetId);
    if (subnet.subscribed) {
      return;
    }
    subnet.subscribed = true;
    waggleNetwork
        .subscribe(
            subnet.topic,
            (peer, objectId, metadata, pieces) ->
                onMessage(subnetId, peer, objectId, metadata, pieces))
        .finish(error -> LOG.warn("Failed to subscribe to Waggle topic {}", subnet.topic, error));
  }

  public synchronized void unsubscribe(final int subnetId) {
    final SubnetState subnet = subnets.get(subnetId);
    if (subnet == null || !subnet.subscribed) {
      return;
    }
    subnet.subscribed = false;
    waggleNetwork
        .unsubscribe(subnet.topic)
        .finish(
            error -> LOG.debug("Failed to unsubscribe from Waggle topic {}", subnet.topic, error));
  }

  public synchronized void unsubscribeAll() {
    List.copyOf(subnets.keySet()).forEach(this::unsubscribe);
  }

  private SubnetState subnet(final int subnetId) {
    return subnets.computeIfAbsent(
        subnetId, __ -> new SubnetState(subnetId, topicForSubnet.apply(subnetId)));
  }

  // ---- Votes we validated ---------------------------------------------------------------------

  /**
   * Hands over an attestation that already passed validation: our own, or one from gossipsub. The
   * subnet does not have to be subscribed; pushing only needs peers that are.
   */
  public synchronized void submit(final int subnetId, final SingleAttestation attestation) {
    final UInt64 slot = attestation.getData().getSlot();
    if (!inWindow(slot)) {
      return;
    }
    store(
        subnet(subnetId),
        slot,
        new Vote(attestation.getData().hashTreeRoot(), attestation.getValidatorIndexRequired()),
        attestation.getFirstCommitteeIndex(),
        attestation.getData(),
        attestation.getSignature(),
        Optional.empty());
  }

  private boolean store(
      final SubnetState subnet,
      final UInt64 slot,
      final Vote vote,
      final UInt64 committeeIndex,
      final AttestationData data,
      final BLSSignature signature,
      final Optional<NodeId> from) {
    prune();
    final SlotState slotState = subnet.slots.computeIfAbsent(slot, __ -> new SlotState());
    from.ifPresent(peer -> slotState.peer(peer).known.add(vote));
    if (slotState.votes.containsKey(vote)
        || slotState.votesPerValidator.getOrDefault(vote.validator(), 0)
            >= MAX_VOTES_PER_VALIDATOR) {
      return false;
    }
    final UInt64 now = timeProvider.getTimeInMillis();
    slotState.votes.put(vote, new StoredVote(signature, now));
    slotState.votesPerValidator.merge(vote.validator(), 1, Integer::sum);
    slotState.roots.putIfAbsent(vote.dataRoot(), new RootInfo(committeeIndex, data));
    slotState.asks.remove(vote);
    slotState.lastArrivalMillis = now;
    slotState.pendingPush.add(vote);
    schedulePush();
    scheduleAdvertise();
    return true;
  }

  private boolean inWindow(final UInt64 slot) {
    return currentSlotSupplier
        .get()
        .map(
            current ->
                slot.isLessThanOrEqualTo(current.plus(1))
                    && slot.plus(config.retainedSlots()).isGreaterThanOrEqualTo(current))
        .orElse(false);
  }

  private void prune() {
    currentSlotSupplier
        .get()
        .ifPresent(
            current -> {
              final UInt64 oldestKept = current.minusMinZero(config.retainedSlots());
              subnets.values().forEach(subnet -> subnet.slots.headMap(oldestKept, false).clear());
            });
  }

  // ---- Push -----------------------------------------------------------------------------------

  private void schedulePush() {
    if (pushScheduled) {
      return;
    }
    pushScheduled = true;
    asyncRunner
        .runAfterDelay(this::pushTick, config.pushInterval())
        .finish(error -> LOG.error("Waggle attestation push failed", error));
  }

  private synchronized void pushTick() {
    pushScheduled = false;
    for (final SubnetState subnet : subnets.values()) {
      if (subnet.slots.values().stream().anyMatch(slot -> !slot.pendingPush.isEmpty())) {
        waggleNetwork
            .getSubscribers(subnet.topic)
            .thenAccept(peers -> pushTo(subnet, peers))
            .finish(error -> LOG.debug("Waggle attestation push failed", error));
      }
    }
  }

  private synchronized void pushTo(final SubnetState subnet, final Collection<NodeId> peers) {
    subnet.slots.forEach(
        (slot, slotState) -> {
          // peer -> data root -> votes
          final Map<NodeId, Map<Bytes32, List<Vote>>> perPeer = new HashMap<>();
          for (final Vote vote : slotState.pendingPush) {
            final List<NodeId> candidates =
                new ArrayList<>(
                    peers.stream()
                        .filter(peer -> !slotState.peer(peer).known.contains(vote))
                        .toList());
            Collections.shuffle(candidates, random);
            candidates.stream()
                .limit(config.pushPeers())
                .forEach(
                    peer -> {
                      slotState.peer(peer).known.add(vote);
                      perPeer
                          .computeIfAbsent(peer, __ -> new LinkedHashMap<>())
                          .computeIfAbsent(vote.dataRoot(), __ -> new ArrayList<>())
                          .add(vote);
                    });
          }
          slotState.pendingPush.clear();
          perPeer.forEach(
              (peer, byRoot) ->
                  byRoot.forEach(
                      (root, votes) -> sendBundles(subnet, slot, slotState, peer, root, votes)));
        });
  }

  private void sendBundles(
      final SubnetState subnet,
      final UInt64 slot,
      final SlotState slotState,
      final NodeId peer,
      final Bytes32 root,
      final List<Vote> votes) {
    final RootInfo rootInfo = slotState.roots.get(root);
    for (int from = 0; from < votes.size(); from += AttestationBundle.MAX_ATTESTATIONS_PER_BUNDLE) {
      final List<Vote> chunk =
          votes.subList(
              from, Math.min(votes.size(), from + AttestationBundle.MAX_ATTESTATIONS_PER_BUNDLE));
      final AttestationBundle bundle =
          bundleSchema.create(
              rootInfo.committeeIndex(),
              rootInfo.data(),
              chunk.stream().map(Vote::validator).toList(),
              chunk.stream().map(vote -> slotState.votes.get(vote).signature()).toList());
      send(subnet, peer, slot, Optional.empty(), Optional.of(bundle.sszSerialize()));
      eventCounter.labels("bundle_sent").inc();
      eventCounter.labels("attestation_sent").inc(chunk.size());
    }
  }

  // ---- Advertise ------------------------------------------------------------------------------

  private void scheduleAdvertise() {
    if (advertiseScheduled) {
      return;
    }
    advertiseScheduled = true;
    asyncRunner
        .runAfterDelay(this::advertiseTick, config.metadataInterval())
        .finish(error -> LOG.error("Waggle attestation advertisement failed", error));
  }

  private synchronized void advertiseTick() {
    advertiseScheduled = false;
    final UInt64 now = timeProvider.getTimeInMillis();
    boolean anyFresh = false;
    for (final SubnetState subnet : subnets.values()) {
      if (subnet.slots.values().stream().anyMatch(slot -> isFresh(slot.lastArrivalMillis, now))) {
        anyFresh = true;
        waggleNetwork
            .getSubscribers(subnet.topic)
            .thenAccept(peers -> advertiseTo(subnet, peers))
            .finish(error -> LOG.debug("Waggle attestation advertisement failed", error));
      }
    }
    if (anyFresh) {
      // Keep ticking while there is something to advertise; otherwise store() restarts it.
      scheduleAdvertise();
    }
  }

  private boolean isFresh(final UInt64 arrivalMillis, final UInt64 now) {
    return arrivalMillis.plus(config.advertWindow().toMillis()).isGreaterThan(now);
  }

  private synchronized void advertiseTo(final SubnetState subnet, final Collection<NodeId> peers) {
    final List<NodeId> chosen = new ArrayList<>(peers);
    Collections.shuffle(chosen, random);
    final UInt64 now = timeProvider.getTimeInMillis();
    chosen.stream()
        .limit(config.metadataPeers())
        .forEach(
            peer ->
                subnet.slots.forEach(
                    (slot, slotState) -> {
                      if (!isFresh(slotState.lastArrivalMillis, now)) {
                        return;
                      }
                      final PeerView view = slotState.peer(peer);
                      final Map<Bytes32, List<UInt64>> availableByRoot = new LinkedHashMap<>();
                      slotState.votes.forEach(
                          (vote, stored) -> {
                            if (isFresh(stored.arrivalMillis(), now) && view.known.add(vote)) {
                              availableByRoot
                                  .computeIfAbsent(vote.dataRoot(), __ -> new ArrayList<>())
                                  .add(vote.validator());
                            }
                          });
                      availableByRoot.forEach(
                          (root, available) -> {
                            sendMetadata(subnet, peer, slot, slotState, root, available, List.of());
                            eventCounter.labels("available_sent").inc();
                          });
                    }));
  }

  private void sendMetadata(
      final SubnetState subnet,
      final NodeId peer,
      final UInt64 slot,
      final SlotState slotState,
      final Bytes32 root,
      final List<UInt64> available,
      final List<UInt64> requests) {
    final CommitteeAttestationPartsMetadata metadata =
        metadataSchema.create(
            slotState.roots.get(root).committeeIndex(), root, available, requests);
    send(subnet, peer, slot, Optional.of(metadata.sszSerialize()), Optional.empty());
  }

  private void send(
      final SubnetState subnet,
      final NodeId peer,
      final UInt64 slot,
      final Optional<Bytes> metadata,
      final Optional<Bytes> pieces) {
    waggleNetwork
        .send(subnet.topic, peer, encodeSlot(slot), metadata, pieces)
        .finish(
            error -> {
              // The peer unsubscribed or its queue is full. It can still get the vote from an
              // advertisement, or over gossipsub.
              eventCounter.labels("send_failed").inc();
              LOG.trace("Waggle send to {} failed", peer, error);
            });
  }

  static Bytes encodeSlot(final UInt64 slot) {
    return Bytes.ofUnsignedLong(slot.longValue(), ByteOrder.LITTLE_ENDIAN);
  }

  // ---- Frames from peers ----------------------------------------------------------------------

  private synchronized void onMessage(
      final int subnetId,
      final NodeId peer,
      final Bytes objectId,
      final Optional<Bytes> metadata,
      final Optional<Bytes> pieces) {
    if (objectId.size() != Long.BYTES) {
      invalidFrame(peer, "object id is not an 8 byte slot");
      return;
    }
    final UInt64 slot = UInt64.fromLongBits(objectId.toLong(ByteOrder.LITTLE_ENDIAN));
    if (!inWindow(slot)) {
      eventCounter.labels("frame_outside_window").inc();
      return;
    }
    final SubnetState subnet = subnet(subnetId);
    pieces
        .filter(bytes -> !bytes.isEmpty())
        .ifPresent(bytes -> handleBundle(subnet, slot, peer, bytes));
    metadata
        .filter(bytes -> !bytes.isEmpty())
        .ifPresent(bytes -> handleMetadata(subnet, slot, peer, bytes));
  }

  private void handleBundle(
      final SubnetState subnet, final UInt64 slot, final NodeId peer, final Bytes bytes) {
    final AttestationBundle bundle;
    try {
      bundle = bundleSchema.sszDeserialize(bytes);
    } catch (final RuntimeException e) {
      invalidFrame(peer, "undecodable bundle");
      return;
    }
    final List<UInt64> attesters = bundle.getAttesterIndices();
    final List<BLSSignature> signatures = bundle.getSignatures();
    if (attesters.isEmpty()
        || attesters.size() > AttestationBundle.MAX_ATTESTATIONS_PER_BUNDLE
        || attesters.size() != signatures.size()
        || new HashSet<>(attesters).size() != attesters.size()
        || !bundle.getData().getSlot().equals(slot)) {
      invalidFrame(peer, "malformed bundle");
      return;
    }
    eventCounter.labels("bundle_received").inc();
    final AttestationData data = bundle.getData();
    final Bytes32 root = data.hashTreeRoot();
    final SlotState slotState = subnet.slots.computeIfAbsent(slot, __ -> new SlotState());
    final PeerView view = slotState.peer(peer);
    for (int i = 0; i < attesters.size(); i++) {
      final Vote vote = new Vote(root, attesters.get(i));
      view.known.add(vote);
      if (slotState.votes.containsKey(vote)
          || slotState.votesPerValidator.getOrDefault(vote.validator(), 0)
              >= MAX_VOTES_PER_VALIDATOR) {
        eventCounter.labels("attestation_duplicate").inc();
        continue;
      }
      // Before validation the identity includes the signature: a bad signature for a vote must
      // not suppress a later good one.
      final Attempt attempt = new Attempt(vote, signatures.get(i));
      if (!slotState.attempts.add(attempt)) {
        eventCounter.labels("attestation_duplicate").inc();
        continue;
      }
      final SingleAttestation attestation =
          attestationSchema.create(
              bundle.getCommitteeIndex(), vote.validator(), data, attempt.signature());
      validator
          .validate(subnet.subnetId, attestation)
          .finish(
              result -> onValidated(subnet, slot, attempt, attestation, peer, result),
              error -> {
                LOG.debug("Validation of an attestation from Waggle failed", error);
                forgetAttempt(subnet, slot, attempt);
              });
    }
  }

  private synchronized void onValidated(
      final SubnetState subnet,
      final UInt64 slot,
      final Attempt attempt,
      final SingleAttestation attestation,
      final NodeId peer,
      final InternalValidationResult result) {
    if (result.isAccept()) {
      forgetAttempt(subnet, slot, attempt);
      eventCounter.labels("attestation_valid").inc();
      if (inWindow(slot)) {
        store(
            subnet,
            slot,
            attempt.vote(),
            attestation.getFirstCommitteeIndex(),
            attestation.getData(),
            attempt.signature(),
            Optional.of(peer));
      }
      gossipPublisher.publish(subnet.subnetId, attestation);
    } else if (result.isReject()) {
      // The attempt stays recorded so the same bad signature is not validated again.
      // TODO: feed this into peer scoring; the sender is at fault.
      eventCounter.labels("attestation_invalid").inc();
      LOG.debug("Peer {} sent an invalid attestation over Waggle: {}", peer, result);
    } else {
      // Ignored (typically already seen over gossipsub) or saved for later: not the peer's fault.
      forgetAttempt(subnet, slot, attempt);
      eventCounter.labels("attestation_ignored").inc();
    }
  }

  private synchronized void forgetAttempt(
      final SubnetState subnet, final UInt64 slot, final Attempt attempt) {
    final SlotState slotState = subnet.slots.get(slot);
    if (slotState != null) {
      slotState.attempts.remove(attempt);
    }
  }

  private void handleMetadata(
      final SubnetState subnet, final UInt64 slot, final NodeId peer, final Bytes bytes) {
    final CommitteeAttestationPartsMetadata metadata;
    try {
      metadata = metadataSchema.sszDeserialize(bytes);
    } catch (final RuntimeException e) {
      invalidFrame(peer, "undecodable metadata");
      return;
    }
    final SlotState slotState = subnet.slots.get(slot);
    final Bytes32 root = metadata.getDataRoot();
    // Lists for a data root we hold no validated vote for are dropped, as Prysm does: the first
    // vote of a root arrives by push, and until then we could not tell junk roots from real ones.
    if (slotState == null || !slotState.roots.containsKey(root)) {
      eventCounter.labels("metadata_unknown_root").inc();
      return;
    }
    final PeerView view = slotState.peer(peer);
    final UInt64 now = timeProvider.getTimeInMillis();

    final List<UInt64> toRequest = new ArrayList<>();
    for (final UInt64 validator : metadata.getAvailable()) {
      final Vote vote = new Vote(root, validator);
      view.known.add(vote);
      if (slotState.votes.containsKey(vote)) {
        continue;
      }
      final Map<NodeId, UInt64> asked = slotState.asks.computeIfAbsent(vote, __ -> new HashMap<>());
      final long open =
          asked.values().stream()
              .filter(at -> at.plus(config.requestTimeout().toMillis()).isGreaterThan(now))
              .count();
      if (open < config.requestPeers() && !asked.containsKey(peer)) {
        asked.put(peer, now);
        toRequest.add(validator);
      }
    }
    if (!toRequest.isEmpty()) {
      sendMetadata(subnet, peer, slot, slotState, root, List.of(), toRequest);
      eventCounter.labels("request_sent").inc();
    }

    final List<Vote> toServe =
        metadata.getRequests().stream()
            .map(validator -> new Vote(root, validator))
            .filter(slotState.votes::containsKey)
            .toList();
    if (!toServe.isEmpty()) {
      toServe.forEach(view.known::add);
      sendBundles(subnet, slot, slotState, peer, root, toServe);
      eventCounter.labels("request_served").inc();
    }
  }

  private void invalidFrame(final NodeId peer, final String reason) {
    // TODO: feed this into peer scoring.
    eventCounter.labels("frame_invalid").inc();
    LOG.debug("Peer {} sent an invalid Waggle attestation frame: {}", peer, reason);
  }
}

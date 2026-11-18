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

package tech.pegasys.teku.networking.eth2.gossip.subnets;

import com.google.common.annotations.VisibleForTesting;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.bytes.Bytes4;
import tech.pegasys.teku.networking.eth2.gossip.encoding.GossipEncoding;
import tech.pegasys.teku.networking.eth2.gossip.topics.GossipTopicName;
import tech.pegasys.teku.networking.eth2.gossip.topics.GossipTopics;
import tech.pegasys.teku.networking.eth2.gossip.topics.OperationProcessor;
import tech.pegasys.teku.networking.eth2.gossip.topics.topichandlers.Eth2TopicHandler;
import tech.pegasys.teku.networking.eth2.gossip.topics.topichandlers.SingleAttestationTopicHandler;
import tech.pegasys.teku.networking.eth2.gossip.waggle.WaggleAttestationPropagator;
import tech.pegasys.teku.networking.p2p.gossip.GossipNetwork;
import tech.pegasys.teku.networking.p2p.gossip.TopicChannel;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.attestation.ValidatableAttestation;
import tech.pegasys.teku.spec.datastructures.operations.Attestation;
import tech.pegasys.teku.spec.datastructures.operations.AttestationSchema;
import tech.pegasys.teku.spec.datastructures.state.ForkInfo;
import tech.pegasys.teku.spec.schemas.SchemaDefinitions;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsElectra;
import tech.pegasys.teku.statetransition.util.DebugDataDumper;
import tech.pegasys.teku.storage.client.RecentChainData;

public class AttestationSubnetSubscriptions extends CommitteeSubnetSubscriptions {

  private final AsyncRunner asyncRunner;
  private final RecentChainData recentChainData;
  private final OperationProcessor<ValidatableAttestation> processor;
  private final ForkInfo forkInfo;
  private final Bytes4 forkDigest;
  private final AttestationSchema<? extends Attestation> attestationSchema;
  private final DebugDataDumper debugDataDumper;
  private final Optional<WaggleAttestationPropagator> wagglePropagator;
  private final Set<Integer> waggleSubnets = new HashSet<>();
  private boolean waggleSubscribed = false;

  public AttestationSubnetSubscriptions(
      final Spec spec,
      final AsyncRunner asyncRunner,
      final GossipNetwork gossipNetwork,
      final GossipEncoding gossipEncoding,
      final RecentChainData recentChainData,
      final OperationProcessor<ValidatableAttestation> processor,
      final ForkInfo forkInfo,
      final Bytes4 forkDigest,
      final DebugDataDumper debugDataDumper) {
    this(
        spec,
        asyncRunner,
        gossipNetwork,
        gossipEncoding,
        recentChainData,
        processor,
        forkInfo,
        forkDigest,
        debugDataDumper,
        Optional.empty());
  }

  /**
   * @param wagglePropagator if present, subnet attestations also propagate over Waggle (EIP-8334
   *     prototype): it follows the subnet subscriptions, gets every attestation that passes gossip
   *     validation and every attestation we publish.
   */
  public AttestationSubnetSubscriptions(
      final Spec spec,
      final AsyncRunner asyncRunner,
      final GossipNetwork gossipNetwork,
      final GossipEncoding gossipEncoding,
      final RecentChainData recentChainData,
      final OperationProcessor<ValidatableAttestation> processor,
      final ForkInfo forkInfo,
      final Bytes4 forkDigest,
      final DebugDataDumper debugDataDumper,
      final Optional<WaggleAttestationPropagator> wagglePropagator) {
    super(gossipNetwork, gossipEncoding);
    this.wagglePropagator = wagglePropagator;
    this.asyncRunner = asyncRunner;
    this.recentChainData = recentChainData;
    this.processor = processor;
    this.forkInfo = forkInfo;
    this.forkDigest = forkDigest;
    final SchemaDefinitions schemaDefinitions =
        spec.atEpoch(forkInfo.getFork().getEpoch()).getSchemaDefinitions();
    attestationSchema =
        spec.atEpoch(forkInfo.getFork().getEpoch())
            .getSchemaDefinitions()
            .toVersionElectra()
            .<AttestationSchema<? extends Attestation>>map(
                SchemaDefinitionsElectra::getSingleAttestationSchema)
            .orElse(schemaDefinitions.getAttestationSchema());
    this.debugDataDumper = debugDataDumper;
  }

  public SafeFuture<?> gossip(final Attestation attestation) {
    return computeSubnetForAttestation(attestation)
        .thenCompose(
            subnetId -> {
              if (subnetId.isEmpty()) {
                throw new IllegalStateException(
                    "Unable to calculate the subnet ID for attestation in slot "
                        + attestation.getData().getSlot()
                        + " because the state was not available");
              }
              final String topic =
                  GossipTopics.getAttestationSubnetTopic(
                      forkDigest, subnetId.get(), gossipEncoding);
              submitToWaggle(subnetId.get(), attestation);
              return gossipNetwork.gossip(topic, gossipEncoding.encode(attestation));
            });
  }

  @VisibleForTesting
  SafeFuture<Optional<TopicChannel>> getChannel(final Attestation attestation) {
    return computeSubnetForAttestation(attestation)
        .thenApply(subnetId -> subnetId.flatMap(this::getChannelForSubnet));
  }

  private void submitToWaggle(final int subnetId, final Attestation attestation) {
    if (attestation.isSingleAttestation()) {
      wagglePropagator.ifPresent(
          waggle -> waggle.submit(subnetId, attestation.toSingleAttestationRequired()));
    }
  }

  @Override
  public synchronized void subscribeToSubnetId(final int subnetId) {
    super.subscribeToSubnetId(subnetId);
    if (waggleSubscribed) {
      wagglePropagator.ifPresent(waggle -> waggle.subscribe(subnetId));
    }
    waggleSubnets.add(subnetId);
  }

  @Override
  public synchronized void unsubscribeFromSubnetId(final int subnetId) {
    super.unsubscribeFromSubnetId(subnetId);
    waggleSubnets.remove(subnetId);
    wagglePropagator.ifPresent(waggle -> waggle.unsubscribe(subnetId));
  }

  @Override
  public synchronized void subscribe() {
    super.subscribe();
    waggleSubscribed = true;
    wagglePropagator.ifPresent(waggle -> waggleSubnets.forEach(waggle::subscribe));
  }

  @Override
  public synchronized void unsubscribe() {
    super.unsubscribe();
    waggleSubscribed = false;
    wagglePropagator.ifPresent(WaggleAttestationPropagator::unsubscribeAll);
  }

  @Override
  protected Eth2TopicHandler<?> createTopicHandler(final int subnetId) {
    // An attestation that passes gossip validation is also offered to peers over Waggle.
    final OperationProcessor<ValidatableAttestation> bridgingProcessor =
        wagglePropagator.isEmpty()
            ? processor
            : (attestation, arrivalTimestamp) ->
                processor
                    .process(attestation, arrivalTimestamp)
                    .thenPeek(
                        result -> {
                          if (result.isAccept()) {
                            submitToWaggle(subnetId, attestation.getUnconvertedAttestation());
                          }
                        });
    return SingleAttestationTopicHandler.createHandler(
        recentChainData,
        asyncRunner,
        bridgingProcessor,
        gossipEncoding,
        forkInfo,
        forkDigest,
        GossipTopicName.getAttestationSubnetTopicName(subnetId),
        attestationSchema,
        subnetId,
        debugDataDumper);
  }

  private SafeFuture<Optional<Integer>> computeSubnetForAttestation(final Attestation attestation) {
    return recentChainData
        .retrieveStateInEffectAtSlot(attestation.getData().getSlot())
        .thenApply(
            state ->
                state.map(
                    s -> recentChainData.getSpec().computeSubnetForAttestation(s, attestation)));
  }

  @VisibleForTesting
  AttestationSchema<? extends Attestation> getAttestationSchema() {
    return attestationSchema;
  }
}

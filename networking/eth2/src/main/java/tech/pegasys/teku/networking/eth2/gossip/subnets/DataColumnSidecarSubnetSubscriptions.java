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

import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.bytes.Bytes4;
import tech.pegasys.teku.networking.eth2.gossip.encoding.GossipEncoding;
import tech.pegasys.teku.networking.eth2.gossip.topics.GossipTopicName;
import tech.pegasys.teku.networking.eth2.gossip.topics.GossipTopics;
import tech.pegasys.teku.networking.eth2.gossip.topics.OperationProcessor;
import tech.pegasys.teku.networking.eth2.gossip.topics.topichandlers.DataColumnSidecarTopicHandler;
import tech.pegasys.teku.networking.eth2.gossip.topics.topichandlers.Eth2TopicHandler;
import tech.pegasys.teku.networking.p2p.gossip.GossipNetwork;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecarSchema;
import tech.pegasys.teku.spec.datastructures.state.ForkInfo;
import tech.pegasys.teku.spec.logic.versions.fulu.helpers.MiscHelpersFulu;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsFulu;
import tech.pegasys.teku.statetransition.util.DebugDataDumper;
import tech.pegasys.teku.storage.client.RecentChainData;

public class DataColumnSidecarSubnetSubscriptions extends CommitteeSubnetSubscriptions {

  private final AsyncRunner asyncRunner;
  private final RecentChainData recentChainData;
  private final OperationProcessor<DataColumnSidecar> processor;
  private final ForkInfo forkInfo;
  private final Bytes4 forkDigest;
  private final DataColumnSidecarSchema<DataColumnSidecar> dataColumnSidecarSchema;
  private final DebugDataDumper debugDataDumper;
  private final MiscHelpersFulu miscHelpersFulu;
  private final boolean partialMessagesEnabled;

  public DataColumnSidecarSubnetSubscriptions(
      final Spec spec,
      final AsyncRunner asyncRunner,
      final GossipNetwork gossipNetwork,
      final GossipEncoding gossipEncoding,
      final RecentChainData recentChainData,
      final OperationProcessor<DataColumnSidecar> processor,
      final DebugDataDumper debugDataDumper,
      final ForkInfo forkInfo,
      final Bytes4 forkDigest,
      final boolean partialMessagesEnabled) {
    super(gossipNetwork, gossipEncoding);
    this.asyncRunner = asyncRunner;
    this.recentChainData = recentChainData;
    this.processor = processor;
    this.debugDataDumper = debugDataDumper;
    this.forkInfo = forkInfo;
    this.forkDigest = forkDigest;
    this.partialMessagesEnabled = partialMessagesEnabled;
    final SpecVersion specVersion =
        spec.forMilestone(spec.getForkSchedule().getHighestSupportedMilestone());
    this.dataColumnSidecarSchema =
        SchemaDefinitionsFulu.required(specVersion.getSchemaDefinitions())
            .getDataColumnSidecarSchema();
    this.miscHelpersFulu =
        MiscHelpersFulu.required(spec.forMilestone(SpecMilestone.FULU).miscHelpers());
  }

  public SafeFuture<?> gossip(final DataColumnSidecar sidecar) {
    int subnetId = computeSubnetForSidecar(sidecar);
    final String topic =
        GossipTopics.getDataColumnSidecarSubnetTopic(forkDigest, subnetId, gossipEncoding);
    return gossipNetwork.gossip(topic, gossipEncoding.encode(sidecar));
  }

  @Override
  protected Eth2TopicHandler<?> createTopicHandler(final int subnetId) {
    final String topicName = GossipTopicName.getDataColumnSidecarSubnetTopicName(subnetId);
    if (partialMessagesEnabled) {
      // Opt this topic in to gossipsub-1.3 partial-messages so peers send PartialMessagesExtension
      // RPCs on it. Must be done before the subscribe is announced.
      final String fullTopic =
          GossipTopics.getDataColumnSidecarSubnetTopic(forkDigest, subnetId, gossipEncoding);
      gossipNetwork.setTopicPartialFlags(fullTopic, true, true);
    }
    return DataColumnSidecarTopicHandler.createHandler(
        recentChainData,
        asyncRunner,
        processor,
        gossipEncoding,
        debugDataDumper,
        forkInfo,
        forkDigest,
        topicName,
        dataColumnSidecarSchema,
        subnetId);
  }

  private int computeSubnetForSidecar(final DataColumnSidecar sidecar) {
    return miscHelpersFulu.computeSubnetForDataColumnSidecar(sidecar.getIndex()).intValue();
  }
}

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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.pegasys.teku.networking.eth2.P2PConfig;
import tech.pegasys.teku.spec.TestSpecFactory;

/** Verifies the CLI flag and P2PConfig wiring for the partial-messages master switch (step 8). */
class PartialDataColumnGossipConfigTest {

  @Test
  void defaultValueIsFalse() {
    assertThat(P2PConfig.DEFAULT_PARTIAL_DATA_COLUMN_GOSSIP_ENABLED).isFalse();
  }

  @Test
  void configBuilderPropagatesFlag() {
    final P2PConfig config =
        P2PConfig.builder()
            .specProvider(TestSpecFactory.createMinimalFulu())
            .partialDataColumnGossipEnabled(true)
            .build();

    assertThat(config.isPartialDataColumnGossipEnabled()).isTrue();
  }

  @Test
  void configBuilderDefaultsToFalse() {
    final P2PConfig config =
        P2PConfig.builder().specProvider(TestSpecFactory.createMinimalFulu()).build();

    assertThat(config.isPartialDataColumnGossipEnabled()).isFalse();
  }
}

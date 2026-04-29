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

import java.util.Locale;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.metrics.StubMetricsSystem;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.networking.eth2.gossip.partialmessages.PartialDataColumnMetrics.CellOutcome;

/** Smoke test for PartialDataColumnMetrics counter wiring (step 11). */
class PartialDataColumnMetricsTest {

  private final StubMetricsSystem metricsSystem = new StubMetricsSystem();
  private final PartialDataColumnMetrics metrics = new PartialDataColumnMetrics(metricsSystem);

  @Test
  void recordCells_novel_incrementsNovelCounter() {
    metrics.recordCells(3, CellOutcome.NOVEL);

    assertThat(
            metricsSystem.getLabelledCounterValue(
                TekuMetricCategory.BEACON,
                "partial_data_column_cells_total",
                CellOutcome.NOVEL.name().toLowerCase(Locale.ROOT)))
        .isEqualTo(3L);
  }

  @Test
  void recordCells_duplicate_incrementsDuplicateCounter() {
    metrics.recordCells(2, CellOutcome.DUPLICATE);

    assertThat(
            metricsSystem.getLabelledCounterValue(
                TekuMetricCategory.BEACON,
                "partial_data_column_cells_total",
                CellOutcome.DUPLICATE.name().toLowerCase(Locale.ROOT)))
        .isEqualTo(2L);
  }

  @Test
  void recordCells_invalid_incrementsInvalidCounter() {
    metrics.recordCells(1, CellOutcome.INVALID);

    assertThat(
            metricsSystem.getLabelledCounterValue(
                TekuMetricCategory.BEACON,
                "partial_data_column_cells_total",
                CellOutcome.INVALID.name().toLowerCase(Locale.ROOT)))
        .isEqualTo(1L);
  }

  @Test
  void recordCells_zeroCount_doesNotIncrement() {
    metrics.recordCells(0, CellOutcome.NOVEL);

    assertThat(
            metricsSystem.getLabelledCounterValue(
                TekuMetricCategory.BEACON,
                "partial_data_column_cells_total",
                CellOutcome.NOVEL.name().toLowerCase(Locale.ROOT)))
        .isZero();
  }

  @Test
  void recordReconstruction_incrementsReconstructionCounter() {
    metrics.recordReconstruction();
    metrics.recordReconstruction();

    assertThat(
            metricsSystem.getCounterValue(
                TekuMetricCategory.BEACON, "partial_data_column_reconstructions_total"))
        .isEqualTo(2L);
  }
}

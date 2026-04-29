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

import java.util.Locale;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;

/**
 * Per-topic counters for the partial-messages extension per §11 of the design doc.
 *
 * <p>Metrics:
 *
 * <ul>
 *   <li>{@code partial_data_column_cells_novel_total} — novel cells merged into the local store
 *   <li>{@code partial_data_column_cells_duplicate_total} — cells that were already present
 *   <li>{@code partial_data_column_cells_invalid_total} — cells/headers that failed validation
 *   <li>{@code partial_data_column_reconstructions_total} — number of full-sidecar reconstructions
 * </ul>
 */
public class PartialDataColumnMetrics {

  private final LabelledMetric<Counter> cellsCounter;
  private final Counter reconstructionsCounter;

  private static final String LABEL_OUTCOME = "outcome";

  public enum CellOutcome {
    NOVEL,
    DUPLICATE,
    INVALID
  }

  public PartialDataColumnMetrics(final MetricsSystem metricsSystem) {
    this.cellsCounter =
        metricsSystem.createLabelledCounter(
            TekuMetricCategory.BEACON,
            "partial_data_column_cells_total",
            "Total number of partial data column cells processed. Includes label 'outcome'.",
            LABEL_OUTCOME);
    this.reconstructionsCounter =
        metricsSystem.createCounter(
            TekuMetricCategory.BEACON,
            "partial_data_column_reconstructions_total",
            "Total number of full DataColumnSidecars reconstructed from partial messages");
  }

  public void recordCells(final int count, final CellOutcome outcome) {
    if (count > 0) {
      cellsCounter.labels(outcome.name().toLowerCase(Locale.ROOT)).inc(count);
    }
  }

  public void recordReconstruction() {
    reconstructionsCounter.inc();
  }
}

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

package tech.pegasys.teku.spec.logic.versions.fulu.util;

import java.util.List;
import tech.pegasys.teku.kzg.KZGCommitment;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;
import tech.pegasys.teku.spec.logic.common.util.PartialDataColumnSidecarUtil;
import tech.pegasys.teku.spec.logic.versions.fulu.helpers.MiscHelpersFulu;

public class PartialDataColumnSidecarUtilFulu implements PartialDataColumnSidecarUtil {

  private final MiscHelpersFulu miscHelpersFulu;

  public PartialDataColumnSidecarUtilFulu(final MiscHelpersFulu miscHelpersFulu) {
    this.miscHelpersFulu = miscHelpersFulu;
  }

  @Override
  public boolean verifyHeaderInclusionProof(final PartialDataColumnHeaderFulu header) {
    return miscHelpersFulu.verifyPartialDataColumnHeaderInclusionProof(header);
  }

  @Override
  public boolean verifyKzgProofs(
      final PartialDataColumnSidecarFulu sidecar,
      final List<KZGCommitment> kzgCommitments,
      final int columnIndex) {
    return miscHelpersFulu.verifyPartialDataColumnSidecarKzgProofs(
        sidecar, kzgCommitments, columnIndex);
  }
}

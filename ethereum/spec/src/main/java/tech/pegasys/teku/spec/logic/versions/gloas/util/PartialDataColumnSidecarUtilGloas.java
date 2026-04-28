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

package tech.pegasys.teku.spec.logic.versions.gloas.util;

import java.util.List;
import tech.pegasys.teku.kzg.KZGCommitment;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;
import tech.pegasys.teku.spec.logic.common.util.PartialDataColumnSidecarUtil;

/**
 * Stub implementation for the Gloas fork. Partial messages for Gloas use a different
 * PartialDataColumnHeader format (slot + beacon_block_root instead of signed_block_header +
 * inclusion proof) and are deferred for MVP. This stub allows the SpecLogic hierarchy to compile
 * without providing a real Gloas implementation.
 */
public class PartialDataColumnSidecarUtilGloas implements PartialDataColumnSidecarUtil {

  @Override
  public boolean verifyHeaderInclusionProof(final PartialDataColumnHeaderFulu header) {
    throw new UnsupportedOperationException(
        "Partial messages for Gloas are not yet implemented (deferred from MVP)");
  }

  @Override
  public boolean verifyKzgProofs(
      final PartialDataColumnSidecarFulu sidecar,
      final List<KZGCommitment> kzgCommitments,
      final int columnIndex) {
    throw new UnsupportedOperationException(
        "Partial messages for Gloas are not yet implemented (deferred from MVP)");
  }
}

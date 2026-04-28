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

package tech.pegasys.teku.spec.logic.common.util;

import java.util.List;
import tech.pegasys.teku.kzg.KZGCommitment;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnHeaderFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.PartialDataColumnSidecarFulu;

public interface PartialDataColumnSidecarUtil {

  /**
   * Verifies the KZG commitments inclusion proof carried in a {@link PartialDataColumnHeaderFulu}.
   *
   * <p>Returns {@code false} when the commitments list is empty (the proof is meaningless) or when
   * the Merkle branch does not match the signed block header's body root.
   */
  boolean verifyHeaderInclusionProof(PartialDataColumnHeaderFulu header);

  /**
   * Batch-verifies the KZG cell proofs for the cells that are present in a {@link
   * PartialDataColumnSidecarFulu} according to its {@code cells_present_bitmap}.
   *
   * @param sidecar the partial sidecar containing bitmap-indexed cells and proofs
   * @param kzgCommitments all KZG commitments for the block (one per blob, indexed by blob index)
   * @param columnIndex the data column index implied by the gossip topic
   * @return {@code true} when all present cells pass KZG verification, or when no cells are present
   */
  boolean verifyKzgProofs(
      PartialDataColumnSidecarFulu sidecar, List<KZGCommitment> kzgCommitments, int columnIndex);
}

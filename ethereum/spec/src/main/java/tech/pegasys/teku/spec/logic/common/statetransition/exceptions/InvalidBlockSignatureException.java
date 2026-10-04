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

package tech.pegasys.teku.spec.logic.common.statetransition.exceptions;

/**
 * The proposer signature of a block did not verify. Raised before the block body is processed, so
 * nothing about the block message itself has been judged: a correctly signed copy of the same
 * message may still be valid.
 */
public class InvalidBlockSignatureException extends StateTransitionException {

  public InvalidBlockSignatureException(final String message) {
    super(message);
  }
}

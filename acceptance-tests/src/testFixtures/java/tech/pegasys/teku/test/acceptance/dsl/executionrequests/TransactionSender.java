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

package tech.pegasys.teku.test.acceptance.dsl.executionrequests;

import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.ethereum.execution.types.Eth1Address;
import tech.pegasys.teku.infrastructure.async.SafeFuture;

/**
 * Signs and submits a transaction to a contract address, hiding the sender's credentials and nonce
 * bookkeeping from the contract wrappers that need to send one. Implemented by {@link
 * ExecutionRequestsService}, which owns both the {@link Eth1Credentials} and the nonce, so a single
 * instance can safely drive transactions to more than one contract.
 */
@FunctionalInterface
interface TransactionSender {

  SafeFuture<String> sendTransaction(
      BigInteger gasPrice, BigInteger gasLimit, Eth1Address to, BigInteger value, Bytes data);
}

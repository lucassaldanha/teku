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

/**
 * Minimal decoding of an {@code eth_getTransactionReceipt} result. Only {@code status} is consumed
 * today (compared against {@code "0x1"}), but {@code transactionHash} and {@code blockNumber} are
 * kept for debuggability when a transaction fails.
 */
public record TransactionReceipt(String transactionHash, String status, String blockNumber) {}

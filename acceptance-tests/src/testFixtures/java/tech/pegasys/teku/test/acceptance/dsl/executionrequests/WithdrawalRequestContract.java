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
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.GWei;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.ethereum.execution.types.Eth1Address;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/*
 Wrapper classed based on https://github.com/lightclient/sys-asm/blob/main/src/withdrawals/main.eas
*/
public class WithdrawalRequestContract {

  private final Eth1Address contractAddress;
  private final Eth1JsonRpcClient client;
  private final TransactionSender transactionSender;

  public WithdrawalRequestContract(
      final Eth1Address contractAddress,
      final Eth1JsonRpcClient client,
      final TransactionSender transactionSender) {
    this.contractAddress = contractAddress;
    this.client = client;
    this.transactionSender = transactionSender;
  }

  public SafeFuture<Integer> getExcessWithdrawalRequests() {
    return client
        .ethCall(contractAddress, Bytes.EMPTY, "latest")
        .thenApply(bytes -> UInt256.fromBytes(bytes).toInt());
  }

  public SafeFuture<String> createWithdrawalRequest(
      final BLSPublicKey publicKey, final UInt64 amount) {
    final BigInteger gasPrice = BigInteger.valueOf(1_000);
    final BigInteger gasLimit = BigInteger.valueOf(1_000_000);
    final Bytes data =
        Bytes.concatenate(publicKey.toBytesCompressed(), GWei.of(amount.longValue()).toBytes());
    final BigInteger value = BigInteger.valueOf(2); // has to be more than current fee (0)

    return transactionSender.sendTransaction(gasPrice, gasLimit, contractAddress, value, data);
  }
}

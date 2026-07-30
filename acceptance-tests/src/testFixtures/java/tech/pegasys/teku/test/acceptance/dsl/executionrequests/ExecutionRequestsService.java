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

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.math.BigInteger;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.ethereum.execution.types.Eth1Address;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.async.Waiter;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

public class ExecutionRequestsService implements AutoCloseable {

  // Increase the poll rate for tx receipts but keep the default 10 min timeout.
  private static final int POLL_INTERVAL_MILLIS = 2000;
  private static final int MAX_POLL_ATTEMPTS = 300;

  private final OkHttpClient httpClient;
  private final ScheduledExecutorService executorService;
  private final Eth1JsonRpcClient client;
  private final Eth1Credentials eth1Credentials;
  private final WithdrawalRequestContract withdrawalRequestContract;
  private final ConsolidationRequestContract consolidationRequestContract;

  // Lazily fetched on the first send, then incremented locally per send, mirroring web3j's
  // FastRawTransactionManager. Not shared across instances: each call site constructs its own
  // ExecutionRequestsService, so a fresh "pending" lookup per instance is correct.
  private UInt64 nextNonce;

  // Serialises sends: each call chains onto the tail of the previous one, so nonce reservation for
  // send N+1 cannot run until send N's nonce has been reserved, even though both are asynchronous.
  private SafeFuture<Void> sendQueue = SafeFuture.COMPLETE;

  public ExecutionRequestsService(
      final String eth1NodeUrl,
      final Eth1Credentials eth1Credentials,
      final Eth1Address withdrawalRequestAddress,
      final Eth1Address consolidationRequestAddress) {
    this.httpClient = new OkHttpClient.Builder().connectionPool(new ConnectionPool()).build();
    this.executorService =
        Executors.newScheduledThreadPool(
            1,
            new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("executionRequests-%d")
                .build());
    this.client = new Eth1JsonRpcClient(eth1NodeUrl, httpClient);
    this.eth1Credentials = eth1Credentials;

    this.withdrawalRequestContract =
        new WithdrawalRequestContract(withdrawalRequestAddress, client, this::sendTransaction);
    this.consolidationRequestContract =
        new ConsolidationRequestContract(
            consolidationRequestAddress, client, this::sendTransaction);
  }

  @Override
  public void close() {
    httpClient.dispatcher().executorService().shutdownNow();
    httpClient.connectionPool().evictAll();
    executorService.shutdownNow();
  }

  public SafeFuture<TransactionReceipt> createWithdrawalRequest(
      final BLSPublicKey publicKey, final UInt64 amount) {
    // Sanity check that we can interact with the contract
    Waiter.waitFor(
        () ->
            assertThat(withdrawalRequestContract.getExcessWithdrawalRequests().get()).isEqualTo(0));

    return withdrawalRequestContract
        .createWithdrawalRequest(publicKey, amount)
        .thenCompose(
            txHash -> {
              waitForSuccessfulTransaction(txHash);
              return getTransactionReceipt(txHash);
            });
  }

  public SafeFuture<TransactionReceipt> createConsolidationRequest(
      final BLSPublicKey sourceValidatorPubkey, final BLSPublicKey targetValidatorPubkey) {
    // Sanity check that we can interact with the contract
    Waiter.waitFor(
        () ->
            assertThat(consolidationRequestContract.getExcessConsolidationRequests().get())
                .isEqualTo(0));

    return consolidationRequestContract
        .createConsolidationRequest(sourceValidatorPubkey, targetValidatorPubkey)
        .thenCompose(
            txHash -> {
              waitForSuccessfulTransaction(txHash);
              return getTransactionReceipt(txHash);
            });
  }

  private synchronized SafeFuture<String> sendTransaction(
      final BigInteger gasPrice,
      final BigInteger gasLimit,
      final Eth1Address to,
      final BigInteger value,
      final Bytes data) {
    final SafeFuture<String> result =
        sendQueue
            .thenCompose(__ -> reserveNonce())
            .thenCompose(
                nonce -> {
                  final Bytes signedTx =
                      LegacyTransactionSigner.sign(
                          eth1Credentials, nonce, gasPrice, gasLimit, to, value, data);
                  return client.ethSendRawTransaction(signedTx);
                });
    // Keep the queue moving even if this send failed, so a later send isn't blocked by it.
    sendQueue = result.handle((ignored, error) -> null);
    return result;
  }

  private SafeFuture<UInt64> reserveNonce() {
    if (nextNonce != null) {
      final UInt64 nonce = nextNonce;
      nextNonce = nextNonce.increment();
      return SafeFuture.completedFuture(nonce);
    }
    return client
        .ethGetTransactionCount(eth1Credentials.address(), "pending")
        .thenApply(
            nonce -> {
              nextNonce = nonce.increment();
              return nonce;
            });
  }

  private SafeFuture<TransactionReceipt> getTransactionReceipt(final String txHash) {
    return pollForReceipt(txHash, MAX_POLL_ATTEMPTS);
  }

  private SafeFuture<TransactionReceipt> pollForReceipt(
      final String txHash, final int attemptsRemaining) {
    return client
        .ethGetTransactionReceipt(txHash)
        .thenCompose(
            maybeReceipt -> {
              if (maybeReceipt.isPresent()) {
                return SafeFuture.completedFuture(maybeReceipt.get());
              }
              if (attemptsRemaining <= 1) {
                return SafeFuture.<TransactionReceipt>failedFuture(
                    new NoSuchElementException("No transaction receipt found for " + txHash));
              }
              return delay(POLL_INTERVAL_MILLIS)
                  .thenCompose(__ -> pollForReceipt(txHash, attemptsRemaining - 1));
            });
  }

  private SafeFuture<Void> delay(final long millis) {
    final SafeFuture<Void> future = new SafeFuture<>();
    final var unused =
        executorService.schedule(() -> future.complete(null), millis, TimeUnit.MILLISECONDS);
    return future;
  }

  private void waitForSuccessfulTransaction(final String txHash) {
    Waiter.waitFor(
        () -> {
          final TransactionReceipt transactionReceipt =
              client.ethGetTransactionReceipt(txHash).join().orElseThrow();
          if (!"0x1".equals(transactionReceipt.status())) {
            throw new RuntimeException("Transaction failed");
          }
        },
        1,
        TimeUnit.MINUTES);
  }
}

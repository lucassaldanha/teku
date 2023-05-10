/*
 * Copyright ConsenSys Software Inc., 2023
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

package tech.pegasys.teku.beacon.pow.beku;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthLog;
import tech.pegasys.teku.beacon.pow.AbstractMonitorableEth1Provider;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.time.TimeProvider;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

public class BekuEth1Provider extends AbstractMonitorableEth1Provider {

  // TODO-beku need a reference to Besu api

  protected BekuEth1Provider(TimeProvider timeProvider) {
    super(timeProvider);
  }

  @Override
  public SafeFuture<Optional<EthBlock.Block>> getEth1Block(UInt64 blockNumber) {
    return null;
  }

  @Override
  public SafeFuture<Optional<EthBlock.Block>> getEth1BlockWithRetry(
      UInt64 blockNumber, Duration retryDelay, int maxRetries) {
    return null;
  }

  @Override
  public SafeFuture<Optional<EthBlock.Block>> getEth1Block(String blockHash) {
    return null;
  }

  @Override
  public SafeFuture<Optional<EthBlock.Block>> getEth1BlockWithRetry(
      String blockHash, Duration retryDelay, int maxRetries) {
    return null;
  }

  @Override
  public SafeFuture<EthBlock.Block> getGuaranteedEth1Block(String blockHash) {
    return null;
  }

  @Override
  public SafeFuture<EthBlock.Block> getGuaranteedEth1Block(UInt64 blockNumber) {
    return null;
  }

  @Override
  public SafeFuture<EthBlock.Block> getLatestEth1Block() {
    return null;
  }

  @Override
  public SafeFuture<EthBlock.Block> getGuaranteedLatestEth1Block() {
    return null;
  }

  @Override
  public SafeFuture<EthCall> ethCall(String from, String to, String data, UInt64 blockNumber) {
    return null;
  }

  @Override
  public SafeFuture<BigInteger> getChainId() {
    return null;
  }

  @Override
  public SafeFuture<Boolean> ethSyncing() {
    return null;
  }

  @Override
  public SafeFuture<List<EthLog.LogResult<?>>> ethGetLogs(EthFilter ethFilter) {
    return null;
  }

  @Override
  public SafeFuture<Boolean> validate() {
    return null;
  }
}
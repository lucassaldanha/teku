/*
 * Copyright ConsenSys Software Inc., 2022
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

package tech.pegasys.teku.executionclient;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.service.serviceutils.Service;

public class BesuService extends Service {

  private static final Logger LOG = LogManager.getLogger();

  // TODO-beku we can pass config objects via constructor
  public BesuService() {}

  @Override
  @SuppressWarnings("UnnecessarilyFullyQualified")
  protected tech.pegasys.teku.infrastructure.async.SafeFuture<?> doStart() {
    LOG.info("TODO: Start Besu service");

    // TODO-beku start besu

    // Example of what we want to do here...

    //        BesuController besuController = new BesuController.Builder()
    //                .fromEthNetworkConfig(EthNetworkConfig.getNetworkConfig(NetworkName.MAINNET))
    //                .build();
    //
    //        Runner runner = new RunnerBuilder().besuController(besuController).build();
    //
    //        runner.startExternalServices();
    //        runner.startEthereumMainLoop();

    return SafeFuture.COMPLETE;
  }

  @Override
  @SuppressWarnings("UnnecessarilyFullyQualified")
  protected tech.pegasys.teku.infrastructure.async.SafeFuture<?> doStop() {
    LOG.info("TODO: STOP Besu service");

    // TODO-beku stop besu

    return SafeFuture.COMPLETE;
  }
}

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

import static org.hyperledger.besu.cli.DefaultCommandValues.DEFAULT_KEY_VALUE_STORAGE_NAME;
import static org.hyperledger.besu.cli.DefaultCommandValues.DEFAULT_SECURITY_MODULE;
import static org.hyperledger.besu.cli.DefaultCommandValues.getDefaultBesuDataPath;
import static org.hyperledger.besu.controller.BesuController.DATABASE_PATH;

import com.google.common.base.Suppliers;
import java.io.File;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collections;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.Runner;
import org.hyperledger.besu.RunnerBuilder;
import org.hyperledger.besu.cli.config.EthNetworkConfig;
import org.hyperledger.besu.cli.config.NetworkName;
import org.hyperledger.besu.cli.options.unstable.MetricsCLIOptions;
import org.hyperledger.besu.controller.BesuController;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.KeyPairUtil;
import org.hyperledger.besu.cryptoservices.KeyPairSecurityModule;
import org.hyperledger.besu.cryptoservices.NodeKey;
import org.hyperledger.besu.ethereum.core.MiningParameters;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.ethereum.eth.EthProtocolConfiguration;
import org.hyperledger.besu.ethereum.eth.sync.SyncMode;
import org.hyperledger.besu.ethereum.eth.sync.SynchronizerConfiguration;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.mainnet.FrontierTargetingGasLimitCalculator;
import org.hyperledger.besu.ethereum.p2p.config.NetworkingConfiguration;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProvider;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProviderBuilder;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.metrics.MetricsSystemFactory;
import org.hyperledger.besu.metrics.ObservableMetricsSystem;
import org.hyperledger.besu.plugin.services.BesuConfiguration;
import org.hyperledger.besu.plugin.services.PermissioningService;
import org.hyperledger.besu.plugin.services.SecurityModuleService;
import org.hyperledger.besu.plugin.services.StorageService;
import org.hyperledger.besu.plugin.services.exception.StorageException;
import org.hyperledger.besu.plugin.services.securitymodule.SecurityModule;
import org.hyperledger.besu.services.BesuConfigurationImpl;
import org.hyperledger.besu.services.BesuPluginContextImpl;
import org.hyperledger.besu.services.PermissioningServiceImpl;
import org.hyperledger.besu.services.SecurityModuleServiceImpl;
import org.hyperledger.besu.services.StorageServiceImpl;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.service.serviceutils.Service;

public class BesuService extends Service {

  private static final Logger LOG = LogManager.getLogger();
  private final Path dataPath = getDefaultBesuDataPath(this);
  //  private final MetricCategoryRegistryImpl metricCategoryRegistry = new
  // MetricCategoryRegistryImpl();
  //  private final MetricCategoryConverter metricCategoryConverter = new MetricCategoryConverter();
  private final SecurityModuleServiceImpl securityModuleService = new SecurityModuleServiceImpl();
  private final StorageServiceImpl storageService = new StorageServiceImpl();
  private final PermissioningServiceImpl permissioningService = new PermissioningServiceImpl();
  private ObservableMetricsSystem metricsSystem;
  private KeyValueStorageProvider keyValueStorageProvider;
  private BekuRocksDBPlugin bekuRocksDBPlugin;
  private BesuPluginContextImpl besuPluginContext = new BesuPluginContextImpl();
  private final BesuConfiguration pluginCommonConfiguration =
      new BesuConfigurationImpl(dataDir(), dataDir().resolve(DATABASE_PATH));

  // TODO-beku we can pass config objects via constructor

  public BesuService() {}

  @Override
  @SuppressWarnings("UnnecessarilyFullyQualified")
  protected tech.pegasys.teku.infrastructure.async.SafeFuture<?> doStart() {
    LOG.info("TODO: Start Besu service");

    preparePlugins();

    metricsSystem =
        MetricsSystemFactory.create(MetricsCLIOptions.create().toDomainObject().build());
    // TODO-beku start besu

    BesuController besuController =
        new BesuController.Builder()
            .fromEthNetworkConfig(
                EthNetworkConfig.getNetworkConfig(NetworkName.MAINNET),
                Collections.emptyMap(),
                SyncMode.X_CHECKPOINT)
            .synchronizerConfiguration(new SynchronizerConfiguration.Builder().build())
            .ethProtocolConfiguration(EthProtocolConfiguration.defaultConfig())
            .miningParameters(new MiningParameters.Builder().build())
            .metricsSystem(metricsSystem)
            .privacyParameters(PrivacyParameters.DEFAULT)
            .dataDirectory(dataDir())
            .clock(Clock.systemUTC())
            .transactionPoolConfiguration(TransactionPoolConfiguration.DEFAULT)
            .nodeKey(new NodeKey(securityModule()))
            .storageProvider(keyValueStorageProvider(DEFAULT_KEY_VALUE_STORAGE_NAME))
            .gasLimitCalculator(new FrontierTargetingGasLimitCalculator())
            .evmConfiguration(EvmConfiguration.DEFAULT)
            .networkConfiguration(NetworkingConfiguration.create())
            //
            // .messagePermissioningProviders(permissioningService.getMessagePermissioningProviders())
            .build();

    Runner runner =
        new RunnerBuilder()
            .besuController(besuController)
            .permissioningService(
                permissioningService) // required by RunnerBuilder.buildNodePermissioningController
            .metricsSystem(metricsSystem)
            // TODO-beku add vertx etc for building DefaultP2PNetwork
            .build();

    runner.startExternalServices();
    runner.startEthereumMainLoop();

    return SafeFuture.COMPLETE;
  }

  private void preparePlugins() {
    //    besuPluginContext.addService(PicoCLIOptions.class, new PicoCLIOptionsImpl(commandLine));
    besuPluginContext.addService(SecurityModuleService.class, securityModuleService);
    besuPluginContext.addService(StorageService.class, storageService);
    //    besuPluginContext.addService(MetricCategoryRegistry.class, metricCategoryRegistry);
    besuPluginContext.addService(PermissioningService.class, permissioningService);
    //    besuPluginContext.addService(PrivacyPluginService.class, privacyPluginService);
    //    besuPluginContext.addService(RpcEndpointService.class, rpcEndpointServiceImpl);

    // register built-in plugins
    bekuRocksDBPlugin = new BekuRocksDBPlugin();
    bekuRocksDBPlugin.register(besuPluginContext);
    //    new InMemoryStoragePlugin().register(besuPluginContext);

    besuPluginContext.registerPlugins(pluginsDir());

    // TODO-beku relies on picocli classes
    //    metricCategoryRegistry
    //        .getMetricCategories()
    //        .forEach(metricCategoryConverter::addRegistryCategory);

    // register default security module
    securityModuleService.register(
        DEFAULT_SECURITY_MODULE, Suppliers.memoize(this::defaultSecurityModule));
  }

  private SecurityModule securityModule() {
    final String securityModuleName = "localfile";
    return securityModuleService
        .getByName(securityModuleName)
        .orElseThrow(() -> new RuntimeException("Security Module not found: " + securityModuleName))
        .get();
  }

  private SecurityModule defaultSecurityModule() {
    final File nodePrivateKeyFile = null;
    return new KeyPairSecurityModule(loadKeyPair(nodePrivateKeyFile));
  }

  /**
   * Load key pair from private key. Visible to be accessed by subcommands.
   *
   * @param nodePrivateKeyFile File containing private key
   * @return KeyPair loaded from private key file
   */
  public KeyPair loadKeyPair(final File nodePrivateKeyFile) {
    return KeyPairUtil.loadKeyPair(resolveNodePrivateKeyFile(nodePrivateKeyFile));
  }

  private File resolveNodePrivateKeyFile(final File nodePrivateKeyFile) {
    return Optional.ofNullable(nodePrivateKeyFile)
        .orElseGet(() -> KeyPairUtil.getDefaultKeyFile(dataDir()));
  }

  private KeyValueStorageProvider keyValueStorageProvider(final String name) {
    if (this.keyValueStorageProvider == null) {
      this.keyValueStorageProvider =
          new KeyValueStorageProviderBuilder()
              .withStorageFactory(
                  storageService
                      .getByName(name)
                      .orElseThrow(
                          () ->
                              new StorageException(
                                  "No KeyValueStorageFactory found for key: " + name)))
              .withCommonConfiguration(pluginCommonConfiguration)
              .withMetricsSystem(metricsSystem)
              .build();
    }
    return this.keyValueStorageProvider;
  }

  private Path pluginsDir() {
    final String pluginsDir = System.getProperty("besu.plugins.dir");
    if (pluginsDir == null) {
      return new File(System.getProperty("besu.home", "."), "plugins").toPath();
    } else {
      return new File(pluginsDir).toPath();
    }
  }

  private Path dataDir() {
    return dataPath.toAbsolutePath();
  }

  @Override
  @SuppressWarnings("UnnecessarilyFullyQualified")
  protected tech.pegasys.teku.infrastructure.async.SafeFuture<?> doStop() {
    LOG.info("TODO: STOP Besu service");

    // TODO-beku stop besu

    return SafeFuture.COMPLETE;
  }
}

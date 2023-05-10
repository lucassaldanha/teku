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

package tech.pegasys.teku.executionclient;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.hyperledger.besu.plugin.BesuContext;
import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.services.StorageService;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBKeyValuePrivacyStorageFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBKeyValueStorageFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBMetricsFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBCLIOptions;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBFactoryConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The RocksDb plugin. */
public class BekuRocksDBPlugin implements BesuPlugin {

  private static final Logger LOG = LoggerFactory.getLogger(BekuRocksDBPlugin.class);
  //  private static final String NAME = "rocksdb";

  private final RocksDBCLIOptions options;
  private final List<SegmentIdentifier> ignorableSegments = new ArrayList<>();
  private BesuContext context;
  private RocksDBKeyValueStorageFactory factory;
  private RocksDBKeyValuePrivacyStorageFactory privacyFactory;

  /** Instantiates a newRocksDb plugin. */
  public BekuRocksDBPlugin() {
    this.options = RocksDBCLIOptions.create();
  }

  /**
   * Add ignorable segment identifier.
   *
   * @param ignorable the ignorable
   */
  public void addIgnorableSegmentIdentifier(final SegmentIdentifier ignorable) {
    ignorableSegments.add(ignorable);
  }

  @Override
  public void register(final BesuContext context) {
    LOG.debug("Registering plugin");
    this.context = context;

    // TODO-beku How to get commandLine into BesuService (or pull config into BeaconNodeCommand :/?)
    //    final Optional<PicoCLIOptions> cmdlineOptions = context.getService(PicoCLIOptions.class);

    //    if (cmdlineOptions.isEmpty()) {
    //      throw new IllegalStateException(
    //          "Expecting a PicoCLI options to register CLI options with, but none found.");
    //    }

    //    cmdlineOptions.get().addPicoCLIOptions(NAME, options);
    createFactoriesAndRegisterWithStorageService();

    LOG.debug("Plugin registered.");
  }

  @Override
  public void start() {
    LOG.debug("Starting plugin.");
    if (factory == null) {
      LOG.trace("Applied configuration: {}", options.toString());
      createFactoriesAndRegisterWithStorageService();
    }
  }

  @Override
  public void stop() {
    LOG.debug("Stopping plugin.");

    try {
      if (factory != null) {
        factory.close();
        factory = null;
      }
    } catch (final IOException e) {
      LOG.error("Failed to stop plugin: {}", e.getMessage(), e);
    }

    try {
      if (privacyFactory != null) {
        privacyFactory.close();
        privacyFactory = null;
      }
    } catch (final IOException e) {
      LOG.error("Failed to stop plugin: {}", e.getMessage(), e);
    }
  }

  /**
   * Is high spec enabled.
   *
   * @return the boolean
   */
  public boolean isHighSpecEnabled() {
    return options.isHighSpec();
  }

  private void createAndRegister(final StorageService service) {
    final List<SegmentIdentifier> segments = service.getAllSegmentIdentifiers();

    final Supplier<RocksDBFactoryConfiguration> configuration =
        Suppliers.memoize(options::toDomainObject);
    factory =
        new RocksDBKeyValueStorageFactory(
            configuration,
            segments,
            ignorableSegments,
            RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS);
    privacyFactory = new RocksDBKeyValuePrivacyStorageFactory(factory);

    service.registerKeyValueStorage(factory);
    service.registerKeyValueStorage(privacyFactory);
  }

  private void createFactoriesAndRegisterWithStorageService() {
    context
        .getService(StorageService.class)
        .ifPresentOrElse(
            this::createAndRegister,
            () -> LOG.error("Failed to register KeyValueFactory due to missing StorageService."));
  }
}
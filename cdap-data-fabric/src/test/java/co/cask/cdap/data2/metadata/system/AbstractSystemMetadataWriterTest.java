/*
 * Copyright © 2016-2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.data2.metadata.system;

import co.cask.cdap.api.dataset.DatasetProperties;
import co.cask.cdap.api.dataset.table.TableProperties;
import co.cask.cdap.api.metadata.MetadataScope;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.guice.ConfigModule;
import co.cask.cdap.common.guice.LocationRuntimeModule;
import co.cask.cdap.common.metadata.MetadataRecordV2;
import co.cask.cdap.common.namespace.guice.NamespaceClientRuntimeModule;
import co.cask.cdap.data.runtime.DataSetsModules;
import co.cask.cdap.data.runtime.SystemDatasetRuntimeModule;
import co.cask.cdap.data2.metadata.store.DefaultMetadataStore;
import co.cask.cdap.data2.metadata.store.MetadataStore;
import co.cask.cdap.proto.id.DatasetId;
import co.cask.cdap.security.auth.context.AuthenticationContextModules;
import co.cask.cdap.security.authorization.AuthorizationEnforcementModule;
import co.cask.cdap.security.authorization.AuthorizationTestModule;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Modules;
import org.apache.tephra.TransactionManager;
import org.apache.tephra.runtime.TransactionInMemoryModule;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test AbstractSystemMetadataWriter.
 */
public class AbstractSystemMetadataWriterTest {

  private static TransactionManager txManager;
  private static MetadataStore store;

  @BeforeClass
  public static void setup() {
    CConfiguration cConf = CConfiguration.create();
    Injector injector = Guice.createInjector(
      new ConfigModule(cConf),
      new AuthorizationTestModule(),
      new AuthorizationEnforcementModule().getInMemoryModules(),
      new AuthenticationContextModules().getMasterModule(),
      Modules.override(
        new DataSetsModules().getInMemoryModules()).with(new AbstractModule() {
        @Override
        protected void configure() {
          // Need the distributed metadata store.
          bind(MetadataStore.class).to(DefaultMetadataStore.class);
        }
      }),
      new LocationRuntimeModule().getInMemoryModules(),
      new TransactionInMemoryModule(),
      new SystemDatasetRuntimeModule().getInMemoryModules(),
      new NamespaceClientRuntimeModule().getInMemoryModules()
    );
    txManager = injector.getInstance(TransactionManager.class);
    txManager.startAndWait();
    store = injector.getInstance(MetadataStore.class);
  }

  @AfterClass
  public static void teardown() {
    txManager.stopAndWait();
  }

  @Test
  public void testMetadataOverwrite() {
    DatasetId dsInstance = new DatasetId("ns1", "ds1");
    DatasetSystemMetadataProvider metadaProvider = new DatasetSystemMetadataProvider(
      dsInstance, TableProperties.builder().setTTL(100).build(), 123456L, null, null, "description1");
    new DelegateSystemMetadataWriter(store, dsInstance, metadaProvider).write();

    MetadataRecordV2 expected =
      new MetadataRecordV2(dsInstance, MetadataScope.SYSTEM,
                           ImmutableMap.of(AppSystemMetadataWriter.ENTITY_NAME_KEY, dsInstance.getEntityName(),
                                         AbstractSystemMetadataWriter.DESCRIPTION_KEY, "description1",
                                         AbstractSystemMetadataWriter.CREATION_TIME_KEY, String.valueOf(123456L),
                                         AbstractSystemMetadataWriter.TTL_KEY, "100"), ImmutableSet.of());
    Assert.assertEquals(expected, store.getMetadata(MetadataScope.SYSTEM, dsInstance.toMetadataEntity()));

    // Now remove TTL, and add dsType
    metadaProvider = new DatasetSystemMetadataProvider(
        dsInstance, DatasetProperties.EMPTY, null, "dsType", "description2");
    new DelegateSystemMetadataWriter(store, dsInstance, metadaProvider).write();

    expected =
      new MetadataRecordV2(dsInstance, MetadataScope.SYSTEM,
                           ImmutableMap.of(AppSystemMetadataWriter.ENTITY_NAME_KEY, dsInstance.getEntityName(),
                                         AbstractSystemMetadataWriter.DESCRIPTION_KEY, "description2",
                                         AbstractSystemMetadataWriter.CREATION_TIME_KEY, String.valueOf(123456L),
                                         DatasetSystemMetadataProvider.TYPE, "dsType"), ImmutableSet.of());
    Assert.assertEquals(expected, store.getMetadata(MetadataScope.SYSTEM, dsInstance.toMetadataEntity()));

    store.removeMetadata(dsInstance.toMetadataEntity());
  }
}

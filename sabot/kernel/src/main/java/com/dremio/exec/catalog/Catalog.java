/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.catalog;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.calcite.schema.Function;

import com.dremio.exec.dotfile.View;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.store.PartitionNotFoundException;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.ischema.tables.TablesTable;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.source.proto.SourceConfig;

/**
 * Interface used to retrieve virtual and physical datasets. This is always contextualized to a single user and
 * default schema. Implementations must be thread-safe
 */
public interface Catalog {

  /**
   * Retrieve a table ignoring the default schema.
   *
   * @param key
   * @return A DremioTable if found, otherwise null.
   */
  DremioTable getTableNoResolve(NamespaceKey key);

  /**
   * Retrieve a table, first checking the default schema.
   *
   * @param key
   * @return
   */
  DremioTable getTable(NamespaceKey key);

  /**
   * Retrieve a table
   *
   * @param datasetId
   * @return
   */
  DremioTable getTable(String datasetId);

  /**
   * @return all tables that have been requested from this catalog.
   */
  Iterable<DremioTable> getAllRequestedTables();

  /**
   * Resolve an ambiguous reference using the following rules: if the reference is a single value
   * and a default schema is defined, resolve using the default schema. Otherwise, resolve using the
   * name directly.
   *
   * @param key
   * @return
   */
  NamespaceKey resolveSingle(NamespaceKey key);

  /**
   * Determine whether the container at the given path exists. Note that this first looks to see if
   * the container exists directly via a lookup. However, in the case of sources, we have to do two
   * additional checks. First, we have to check if there is a physical dataset in the path (because
   * in FileSystems, sometimes the folders leading to a dataset don't exist). If that returns false,
   * we finally have to consult the source directly to see if the path exists.
   *
   * @param path Container path to check.
   * @return True if the path exists and is readable by the user. False if it doesn't exist.
   */
  boolean containerExists(NamespaceKey path);

  /**
   * Get a list of all schemas.
   *
   * @param path
   *          The path to contextualize to. If the path has no fields, get all schemas. Note
   *          that this does include nested schemas.
   * @return Iterable list of strings of each schema.
   */
  Iterable<String> listSchemas(NamespaceKey path);

  Iterable<TablesTable.Table> listDatasets(NamespaceKey path);

  /**
   * Get a list of functions. Provided specifically for DremioCatalogReader.
   * @param path
   * @return
   */
  Collection<Function> getFunctions(NamespaceKey path);

  NamespaceKey getDefaultSchema();

  String getUser();

  /**
   * Resolve the provided key to the default schema path, if there is one.
   * @param key
   * @return
   */
  NamespaceKey resolveToDefault(NamespaceKey key);

  /**
   * Return a new Catalog contextualized to the provided username and default schema
   *
   * @param username
   * @param newDefaultSchema
   * @return
   */
  Catalog resolveCatalog(String username, NamespaceKey newDefaultSchema);

  /**
   * Return a new Catalog contextualized to the provided username
   *
   * @param username
   * @return
   */
  Catalog resolveCatalog(String username);

  /**
   * Return a new Catalog contextualized to the provided default schema
   * @param newDefaultSchema
   * @return
   */
  Catalog resolveCatalog(NamespaceKey newDefaultSchema);

  MetadataStatsCollector getMetadataStatsCollector();

  CreateTableEntry createNewTable(final NamespaceKey key, final WriterOptions writerOptions, final Map<String, Object> storageOptions);

  boolean createView(final NamespaceKey key, View view) throws IOException;

  void dropView(final NamespaceKey key) throws IOException;

  void dropTable(NamespaceKey key);

  /**
   * Create a new dataset at this location and mutate the dataset before saving.
   * @param key
   * @param datasetMutator
   */
  void createDataset(NamespaceKey key, com.google.common.base.Function<DatasetConfig, DatasetConfig> datasetMutator);

  StoragePlugin.UpdateStatus refreshDataset(NamespaceKey key, boolean force);

  SourceState refreshSourceStatus(NamespaceKey key) throws Exception;

  Iterable<String> getSubPartitions(NamespaceKey key, List<String> partitionColumns, List<String> partitionValues) throws PartitionNotFoundException;

  /**
   * Create or update a physical dataset along with its read definitions and splits.
   * @param userNamespaceService namespace service for a user who is adding or modifying a dataset.
   * @param source source where dataset is to be created/updated
   * @param datasetPath dataset full path
   * @param datasetConfig minimum configuration needed to define a dataset (format settings)
   * @return true if dataset is created/updated
   * @throws NamespaceException
   */
  boolean createOrUpdateDataset(NamespaceService userNamespaceService, NamespaceKey source, NamespaceKey datasetPath, DatasetConfig datasetConfig) throws NamespaceException;

  /**
   * Get a source based on the provided name. If the source doesn't exist, synchronize with the
   * KVStore to confirm creation status.
   *
   * @param name
   * @return A StoragePlugin casted to the expected output.
   */
  <T extends StoragePlugin> T getSource(String name);

  /**
   * Create a source based on the provided configuration. Includes both the creation as well the
   * startup of the source. If the source fails to start, no creation will be done. The provided
   * configuration should have a null version. Failure to create or failure to start with throw an
   * exception. Additionally, if "store.plugin.check_state" is enabled, a plugin that starts but
   * then reveals a bad state, will also result in exception.
   *
   * @param config
   *          Configuration for the source.
   */
  void createSource(SourceConfig config);

  /**
   * Update an existing source with the given config. The config version must be the same as the
   * currently active source. If it isn't, this call will fail with an exception.
   *
   * @param config Configuration for the source.
   */
  void updateSource(SourceConfig config);

  /**
   * Delete a source with the provided config. If the source doesn't exist or the config doesn't
   * match, the method with throw an exception.
   *
   * @param config
   */
  void deleteSource(SourceConfig config);


  /**
   * Determines if a SourceConfig changes metadata impacting properties compared to the existing SourceConfig.
   *
   * @param sourceConfig source config
   * @return boolean
   */
  boolean isSourceConfigMetadataImpacting(SourceConfig sourceConfig);

  /**
   * Get the cached source state for a plugin.
   *
   * @param name plugin name whose state to retrieve
   * @return Last refreshed source state. Null if source is not found.
   */
  SourceState getSourceState(String name);
}

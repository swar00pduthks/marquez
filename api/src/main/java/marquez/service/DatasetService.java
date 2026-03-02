/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service;

import io.prometheus.client.Counter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.common.models.DatasetName;
import marquez.common.models.JobName;
import marquez.common.models.NamespaceName;
import marquez.common.models.RunId;
import marquez.db.BaseDao;
import marquez.db.DatasetVersionDao;
import marquez.db.RunDao;
import marquez.db.models.ExtendedDatasetVersionRow;
import marquez.db.models.ExtendedRunRow;
import marquez.service.RunTransitionListener.JobOutputUpdate;
import marquez.service.models.Dataset;
import marquez.service.models.DatasetMeta;

@Slf4j
public class DatasetService extends DelegatingDaos.DelegatingDatasetDao {
  private final marquez.db.NamespaceDao namespaceDao;
  private final marquez.db.DatasetDao datasetDao;

  public static final Counter datasets =
      Counter.build()
          .namespace("marquez")
          .name("dataset_total")
          .labelNames("namespace_name", "dataset_type")
          .help("Total number of datasets.")
          .register();
  public static final Counter versions =
      Counter.build()
          .namespace("marquez")
          .name("dataset_versions_total")
          .labelNames("namespace_name", "dataset_type", "dataset_name")
          .help("Total number of dataset versions.")
          .register();

  private final DatasetVersionDao datasetVersionDao;
  private final RunDao runDao;
  private final RunService runService;

  public DatasetService(@NonNull final BaseDao baseDao, @NonNull final RunService runService) {
    super(baseDao.createDatasetDao());
    this.namespaceDao = baseDao.createNamespaceDao();
    this.datasetDao = baseDao.createDatasetDao();
    this.datasetVersionDao = baseDao.createDatasetVersionDao();
    this.runDao = baseDao.createRunDao();
    this.runService = runService;
  }

  /**
   * @deprecated Prefer OpenLineage, see <a
   *     href="https://openlineage.io">https://openlineage.io</a>. This method is scheduled to be
   *     removed in release {@code 0.25.0}.
   */
  public Dataset createOrUpdate(
      @NonNull NamespaceName namespaceName,
      @NonNull DatasetName datasetName,
      @NonNull DatasetMeta datasetMeta) {
    if (datasetMeta.getRunId().isPresent()) {
      UUID runUuid = datasetMeta.getRunId().get().getValue();
      ExtendedRunRow runRow = runDao.findRunByUuidAsExtendedRow(runUuid).get();

      List<ExtendedDatasetVersionRow> outputs =
          datasetVersionDao.findOutputDatasetVersionsFor(runUuid);
      runService.notify(
          new JobOutputUpdate(
              RunId.of(runRow.getUuid()),
              null,
              JobName.of(runRow.getJobName()),
              NamespaceName.of(runRow.getNamespaceName()),
              RunService.buildRunOutputs(outputs)));
    }
    log.info(
        "Creating or updating dataset '{}' for namespace '{}' with meta: {}",
        datasetName.getValue(),
        namespaceName.getValue(),
        datasetMeta);

    return upsertDatasetMeta(namespaceName, datasetName, datasetMeta);
  }

  /** Find the UUID for a namespace by name, or return Optional.empty() if not found. */
  public java.util.Optional<UUID> findNamespaceUuidByName(String namespace) {
    return java.util.Optional.ofNullable(namespaceDao.findNamespaceByName(namespace))
        .flatMap(opt -> opt.map(row -> row.getUuid()));
  }

  /**
   * Find the UUID for a dataset by namespace UUID and dataset name, or return Optional.empty() if
   * not found.
   */
  public java.util.Optional<UUID> findDatasetUuidByName(UUID namespaceUuid, String dataset) {
    return datasetDao.findUuidByName(namespaceUuid, dataset);
  }

  public List<Dataset> findAllDatasetsV2(
      UUID namespaceUuid, int limit, int offset, Set<String> includeFacets) {
    return datasetDao.findAllDatasetsV2(namespaceUuid, limit, offset, includeFacets);
  }

  public Optional<Dataset> findDatasetByNameV2(
      UUID namespaceUuid, String datasetName, Set<String> includeFacets) {
    return datasetDao.findDatasetByNameV2(namespaceUuid, datasetName, includeFacets);
  }

  public int countDatasets(String namespaceName) {
    return datasetDao.countFor(namespaceName);
  }

  public List<Dataset> findAllWithTags(
      String namespaceName, int limit, int offset, java.util.Set<String> includeFacets) {
    return datasetDao.findAllWithTags(namespaceName, limit, offset, includeFacets);
  }

  public java.util.Optional<Dataset> findWithTags(
      String namespaceName, String datasetName, java.util.Set<String> includeFacets) {
    return datasetDao.findWithTags(namespaceName, datasetName, includeFacets);
  }
}

/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service;

import marquez.db.BaseDao;

public class DatasetVersionService extends DelegatingDaos.DelegatingDatasetVersionDao {
  private final marquez.db.DatasetVersionDao datasetVersionDao;

  public DatasetVersionService(BaseDao baseDao) {
    super(baseDao.createDatasetVersionDao());
    this.datasetVersionDao = baseDao.createDatasetVersionDao();
  }

  public java.util.List<marquez.service.models.DatasetVersion> findAllDatasetVersionsV2(
      java.util.UUID datasetUuid, int limit, int offset, java.util.Set<String> includeFacets) {
    return datasetVersionDao.findAllDatasetVersionsV2(datasetUuid, limit, offset, includeFacets);
  }

  public java.util.Optional<marquez.service.models.DatasetVersion> findDatasetVersionByVersionV2(
      java.util.UUID datasetUuid, String version, java.util.Set<String> includeFacets) {
    return datasetVersionDao.findDatasetVersionByVersionV2(datasetUuid, version, includeFacets);
  }

  private static String buildFacetFilter(java.util.Set<String> includeFacets) {
    if (includeFacets == null || includeFacets.isEmpty()) {
      return "";
    }
    String facetList =
        includeFacets.stream()
            .map(f -> "'" + f.replace("'", "''") + "'")
            .collect(java.util.stream.Collectors.joining(","));
    return " AND (df.facet_name IN ("
        + facetList
        + ") OR df.facet_name = 'schema')"; // schema is always included if
    // present
  }

  public java.util.Optional<marquez.service.models.DatasetVersion> findByWithRun(
      java.util.UUID version, java.util.Set<String> includeFacets) {
    String facetFilter = buildFacetFilter(includeFacets);
    java.util.Optional<marquez.service.models.DatasetVersion> v =
        datasetVersionDao.findBy(version, facetFilter);

    v.ifPresent(
        ver -> {
          if (ver.getCreatedByRunUuid() != null) {
            java.util.Optional<marquez.service.models.Run> run =
                datasetVersionDao.createRunDao().findRunByUuid(ver.getCreatedByRunUuid());
            run.ifPresent(ver::setCreatedByRun);
          }
        });
    return v;
  }

  public java.util.List<marquez.service.models.DatasetVersion> findAllWithRun(
      String namespaceName,
      String datasetName,
      int limit,
      int offset,
      java.util.Set<String> includeFacets) {
    String facetFilter = buildFacetFilter(includeFacets);
    java.util.List<marquez.service.models.DatasetVersion> v =
        datasetVersionDao.findAll(namespaceName, datasetName, limit, offset, facetFilter);
    return v.stream()
        .peek(
            ver -> {
              if (ver.getCreatedByRunUuid() != null) {
                java.util.Optional<marquez.service.models.Run> run =
                    datasetVersionDao.createRunDao().findRunByUuid(ver.getCreatedByRunUuid());
                run.ifPresent(ver::setCreatedByRun);
              }
            })
        .collect(java.util.stream.Collectors.toList());
  }
}

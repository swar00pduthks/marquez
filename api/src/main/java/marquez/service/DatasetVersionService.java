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

  public java.util.Optional<marquez.db.models.DatasetVersionRow> findDatasetVersionByVersionV2(
      java.util.UUID datasetUuid, String version) {
    // You may need to implement this in the DAO if not present
    return datasetVersionDao.findDatasetVersionByVersionV2(datasetUuid, version);
  }
}

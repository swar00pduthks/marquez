/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import marquez.db.ColumnLineageDao;
import marquez.db.DatasetDao;
import marquez.db.DatasetFieldDao;
import marquez.db.DatasetVersionDao;
import marquez.db.JobDao;
import marquez.db.JobVersionDao;
import marquez.db.LineageDao;
import marquez.db.NamespaceDao;
import marquez.db.OpenLineageDao;
import marquez.db.RunArgsDao;
import marquez.db.RunDao;
import marquez.db.RunStateDao;
import marquez.db.SourceDao;
import marquez.db.StreamVersionDao;
import marquez.db.TagDao;
import marquez.service.models.Dataset;
import marquez.service.models.DatasetVersion;
import marquez.service.models.Job;

public class DelegatingDaos {
  @RequiredArgsConstructor
  public static class DelegatingDatasetDao implements DatasetDao {
    @Delegate private final DatasetDao delegate;

    @Override
    public List<Dataset> findAllDatasetsV2(
        UUID namespaceUuid, int limit, int offset, Set<String> includeFacets) {
      return delegate.findAllDatasetsV2(namespaceUuid, limit, offset, includeFacets);
    }

    @Override
    public java.util.Optional<Dataset> findDatasetByNameV2(
        UUID namespaceUuid, String datasetName, Set<String> includeFacets) {
      return delegate.findDatasetByNameV2(namespaceUuid, datasetName, includeFacets);
    }
  }

  @RequiredArgsConstructor
  public static class DelegatingDatasetFieldDao implements DatasetFieldDao {
    @Delegate private final DatasetFieldDao delegate;
  }

  @RequiredArgsConstructor
  public static class DelegatingDatasetVersionDao implements DatasetVersionDao {
    @Delegate private final DatasetVersionDao delegate;

    @Override
    public List<DatasetVersion> findAllDatasetVersionsV2(
        UUID datasetUuid, int limit, int offset, Set<String> includeFacets) {
      return delegate.findAllDatasetVersionsV2(datasetUuid, limit, offset, includeFacets);
    }

    @Override
    public java.util.Optional<DatasetVersion> findDatasetVersionByVersionV2(
        UUID datasetUuid, String version, Set<String> includeFacets) {
      return delegate.findDatasetVersionByVersionV2(datasetUuid, version, includeFacets);
    }
  }

  @RequiredArgsConstructor
  public static class DelegatingJobDao implements JobDao {
    @Delegate private final JobDao delegate;

    @Override
    public List<Job> findAllJobsV2(
        UUID namespaceUuid, int limit, int offset, Set<String> includeFacets) {
      return delegate.findAllJobsV2(namespaceUuid, limit, offset, includeFacets);
    }

    @Override
    public java.util.Optional<Job> findJobByNameV2(
        UUID namespaceUuid, String jobName, Set<String> includeFacets) {
      return delegate.findJobByNameV2(namespaceUuid, jobName, includeFacets);
    }
  }

  @RequiredArgsConstructor
  public static class DelegatingJobVersionDao implements JobVersionDao {
    @Delegate private final JobVersionDao delegate;
  }

  @RequiredArgsConstructor
  public static class DelegatingNamespaceDao implements NamespaceDao {
    @Delegate private final NamespaceDao delegate;
  }

  @RequiredArgsConstructor
  public static class DelegatingOpenLineageDao implements OpenLineageDao {
    @Delegate private final OpenLineageDao delegate;
  }

  @RequiredArgsConstructor
  public static class DelegatingRunArgsDao implements RunArgsDao {
    @Delegate private final RunArgsDao delegate;
  }

  @RequiredArgsConstructor
  public static class DelegatingRunDao implements RunDao {
    @Delegate private final RunDao delegate;
  }

  @RequiredArgsConstructor
  public static class DelegatingRunStateDao implements RunStateDao {
    @Delegate private final RunStateDao delegate;
  }

  @RequiredArgsConstructor
  public static class DelegatingSourceDao implements SourceDao {
    @Delegate private final SourceDao delegate;
  }

  @RequiredArgsConstructor
  public static class DelegatingStreamVersionDao implements StreamVersionDao {
    @Delegate private final StreamVersionDao delegate;
  }

  @RequiredArgsConstructor
  public static class DelegatingTagDao implements TagDao {
    @Delegate private final TagDao delegate;
  }

  @RequiredArgsConstructor
  public static class DelegatingLineageDao implements LineageDao {
    @Delegate private final LineageDao delegate;
  }

  @RequiredArgsConstructor
  public static class DelegatingColumnLineageDao implements ColumnLineageDao {
    @Delegate private final ColumnLineageDao delegate;
  }
}

/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api;

import com.google.common.collect.ImmutableSet;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.Optional;
import lombok.NonNull;
import marquez.api.exceptions.DatasetNotFoundException;
import marquez.api.exceptions.FieldNotFoundException;
import marquez.api.exceptions.JobNotFoundException;
import marquez.api.exceptions.NamespaceNotFoundException;
import marquez.api.exceptions.RunAlreadyExistsException;
import marquez.api.exceptions.RunNotFoundException;
import marquez.api.exceptions.SourceNotFoundException;
import marquez.common.models.DatasetFieldId;
import marquez.common.models.DatasetId;
import marquez.common.models.DatasetName;
import marquez.common.models.FieldName;
import marquez.common.models.JobId;
import marquez.common.models.JobName;
import marquez.common.models.NamespaceName;
import marquez.common.models.RunId;
import marquez.common.models.SourceName;
import marquez.service.ColumnLineageService;
import marquez.service.DatasetFieldService;
import marquez.service.DatasetService;
import marquez.service.DatasetVersionService;
import marquez.service.JobService;
import marquez.service.LineageService;
import marquez.service.NamespaceService;
import marquez.service.OpenLineageService;
import marquez.service.RunService;
import marquez.service.ServiceFactory;
import marquez.service.SourceService;
import marquez.service.TagService;
import marquez.service.models.NodeId;
import marquez.service.models.Run;

public class BaseResource {
  protected ServiceFactory serviceFactory;
  protected DatasetService datasetService;
  protected JobService jobService;
  protected NamespaceService namespaceService;
  protected OpenLineageService openLineageService;
  protected RunService runService;
  protected SourceService sourceService;
  protected TagService tagService;
  protected DatasetVersionService datasetVersionService;
  protected DatasetFieldService datasetFieldService;
  protected LineageService lineageService;
  protected ColumnLineageService columnLineageService;

  public BaseResource(ServiceFactory serviceFactory) {
    this.serviceFactory = serviceFactory;
    this.datasetService = serviceFactory.getDatasetService();
    this.jobService = serviceFactory.getJobService();
    this.namespaceService = serviceFactory.getNamespaceService();
    this.openLineageService = serviceFactory.getOpenLineageService();
    this.runService = serviceFactory.getRunService();
    this.sourceService = serviceFactory.getSourceService();
    this.tagService = serviceFactory.getTagService();
    this.datasetVersionService = serviceFactory.getDatasetVersionService();
    this.datasetFieldService = serviceFactory.getDatasetFieldService();
    this.lineageService = serviceFactory.getLineageService();
    this.columnLineageService = serviceFactory.getColumnLineageService();
  }

  void throwIfNotExists(@NonNull NamespaceName namespaceName) {
    if (!namespaceService.exists(namespaceName.getValue())) {
      throw new NamespaceNotFoundException(namespaceName);
    }
  }

  void throwIfNotExists(@NonNull DatasetId datasetId) {
    throwIfNotExists(datasetId.getNamespace(), datasetId.getName());
  }

  void throwIfNotExists(@NonNull NamespaceName namespaceName, @NonNull DatasetName datasetName) {
    if (!datasetService.exists(namespaceName.getValue(), datasetName.getValue())) {
      throw new DatasetNotFoundException(datasetName);
    }
  }

  void throwIfSourceNotExists(SourceName sourceName) {
    if (!sourceService.exists(sourceName.getValue())) {
      throw new SourceNotFoundException(sourceName);
    }
  }

  void throwIfNotExists(@NonNull DatasetFieldId datasetFieldId) {
    throwIfNotExists(
        datasetFieldId.getDatasetId().getNamespace(),
        datasetFieldId.getDatasetId().getName(),
        datasetFieldId.getFieldName());
  }

  void throwIfNotExists(
      @NonNull NamespaceName namespaceName,
      @NonNull DatasetName datasetName,
      @NonNull FieldName fieldName) {
    if (!datasetFieldService.exists(
        namespaceName.getValue(), datasetName.getValue(), fieldName.getValue())) {
      throw new FieldNotFoundException(datasetName, fieldName);
    }
  }

  void throwIfNotExists(@NonNull JobId jobId) {
    throwIfNotExists(jobId.getNamespace(), jobId.getName());
  }

  void throwIfNotExists(@NonNull NamespaceName namespaceName, @NonNull JobName jobName) {
    if (!jobService.exists(namespaceName.getValue(), jobName.getValue())) {
      throw new JobNotFoundException(jobName);
    }
  }

  void throwIfExists(
      @NonNull NamespaceName namespaceName, @NonNull JobName jobName, @Nullable RunId runId) {
    if (runId != null) {
      if (runService.exists(runId.getValue())) {
        throw new RunAlreadyExistsException(namespaceName, jobName, runId);
      }
    }
  }

  void throwIfNotExists(@NonNull RunId runId) {
    if (!runService.exists(runId.getValue())) {
      throw new RunNotFoundException(runId);
    }
  }

  void throwIfJobDoesNotMatchRun(RunId runId, String namespaceName, String jobName) {
    Optional<Run> runRow = runService.findRunByUuid(runId.getValue());
    if (runRow.isEmpty()) {
      throw new RunNotFoundException(runId);
    }
    Run run = runRow.get();
    if (!jobName.equals(run.getJobName()) || !namespaceName.equals(run.getNamespaceName())) {
      throw new RunNotFoundException(runId);
    }
  }

  void throwIfDatasetsNotExist(ImmutableSet<DatasetId> datasets) {
    for (DatasetId datasetId : datasets) {
      if (!datasetService.exists(
          datasetId.getNamespace().getValue(), datasetId.getName().getValue())) {
        throw new DatasetNotFoundException(datasetId.getName());
      }
    }
  }

  void throwIfNotExists(@NonNull NodeId nodeId) {
    if (!nodeId.hasVersion()) {
      if (nodeId.isDatasetType()) {
        throwIfNotExists(nodeId.asDatasetId());
      } else if (nodeId.isDatasetFieldType()) {
        throwIfNotExists(nodeId.asDatasetFieldId());
      } else if (nodeId.isJobType()) {
        throwIfNotExists(nodeId.asJobId());
      } else if (nodeId.isRunType()) {
        throwIfNotExists(nodeId.asRunId());
      }
    }
  }

  URI locationFor(@NonNull UriInfo uriInfo, @NonNull Run run) {
    return uriInfo
        .getBaseUriBuilder()
        .path(JobResource.class)
        .path(RunResource.class, "getRun")
        .build(run.getId().getValue());
  }
}

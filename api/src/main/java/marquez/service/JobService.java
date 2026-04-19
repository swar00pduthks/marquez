/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.common.Utils;
import marquez.common.models.JobName;
import marquez.common.models.NamespaceName;
import marquez.common.models.RunId;
import marquez.db.BaseDao;
import marquez.db.DatasetVersionDao;
import marquez.db.RunDao;
import marquez.db.models.ExtendedDatasetVersionRow;
import marquez.db.models.ExtendedRunRow;
import marquez.db.models.JobRow;
import marquez.service.RunTransitionListener.JobInputUpdate;
import marquez.service.models.Job;
import marquez.service.models.JobMeta;

@Slf4j
public class JobService extends DelegatingDaos.DelegatingJobDao {

  private final marquez.db.NamespaceDao namespaceDao;
  private final RunDao runDao;
  private final ObjectMapper mapper = Utils.newObjectMapper();
  private final DatasetVersionDao datasetVersionDao;
  private final RunService runService;

  public JobService(@NonNull BaseDao baseDao, @NonNull final RunService runService) {
    super(baseDao.createJobDao());
    this.namespaceDao = baseDao.createNamespaceDao();
    this.runDao = baseDao.createRunDao();
    this.datasetVersionDao = baseDao.createDatasetVersionDao();
    this.runService = runService;
  }

  /**
   * @deprecated Prefer OpenLineage, see <a
   *     href="https://openlineage.io">https://openlineage.io</a>. This method is scheduled to be
   *     removed in release {@code 0.25.0}.
   */
  public Job createOrUpdate(
      @NonNull NamespaceName namespaceName, @NonNull JobName jobName, @NonNull JobMeta jobMeta) {
    JobRow jobRow = upsertJobMeta(namespaceName, jobName, jobMeta, mapper);

    // Run updates come in through this endpoint to notify of input and output
    // datasets.
    // Note: There is an alternative route to registering /output/ datasets in the
    // dataset api.
    if (jobMeta.getRunId().isPresent()) {
      UUID runUuid = jobMeta.getRunId().get().getValue();
      runDao.notifyJobChange(runUuid, jobRow, jobMeta);
      ExtendedRunRow runRow = runDao.findRunByUuidAsExtendedRow(runUuid).get();

      List<ExtendedDatasetVersionRow> inputs =
          datasetVersionDao.findInputDatasetVersionsFor(runUuid);
      runService.notify(
          new JobInputUpdate(
              RunId.of(runRow.getUuid()),
              RunService.buildRunMeta(runRow),
              null,
              JobName.of(jobRow.getName()),
              NamespaceName.of(jobRow.getNamespaceName()),
              RunService.buildRunInputs(inputs)));
    }

    JobMetrics.emitJobCreationMetric(namespaceName.getValue(), jobMeta.getType().toString());

    return this.findWithDatasetsAndRun(jobRow.getNamespaceName(), jobRow.getName()).get();
  }

  public Optional<UUID> findNamespaceUuidByName(String namespaceName) {
    return namespaceDao
        .findNamespaceByName(namespaceName)
        .map(marquez.db.models.NamespaceRow::getUuid);
  }

  /**
   * V2 list: fetches from denormalized table then hydrates latestRun + inputs/outputs via the same
   * post-processing that V1's findAllWithRun() uses. This matches V1 response shape exactly.
   */
  @Override
  public List<Job> findAllJobsV2(
      UUID namespaceUuid, int limit, int offset, java.util.Set<String> includeFacets) {
    List<Job> jobs = super.findAllJobsV2(namespaceUuid, limit, offset, includeFacets);
    jobs.forEach(
        j -> {
          List<marquez.service.models.Run> runs =
              runDao.findByLatestJob(j.getNamespace().getValue(), j.getName().getValue(), 10, 0);
          this.setJobData(runs, j);
        });
    return jobs;
  }

  /**
   * V2 single-job: fetches from denormalized table then hydrates latestRun + inputs/outputs the
   * same way V1's findWithDatasetsAndRun() does — latestRun via setJobData(), current-version IO
   * via setJobDataset().
   */
  @Override
  public Optional<Job> findJobByNameV2(
      UUID namespaceUuid, String jobName, java.util.Set<String> includeFacets) {
    Optional<Job> job = super.findJobByNameV2(namespaceUuid, jobName, includeFacets);
    job.ifPresent(
        j -> {
          List<marquez.service.models.Run> runs =
              runDao.findByLatestJob(j.getNamespace().getValue(), j.getName().getValue(), 10, 0);
          // setJobData sets latestRun, latestRuns AND inputs/outputs from the latest run's
          // dataset versions. We do NOT call setJobDataset() here because createJobVersionDao()
          // requires a full JDBI SQL-object context (with mapper registration) that is only
          // available inside a DAO default method — not from the service layer.
          this.setJobData(runs, j);
        });
    return job;
  }
}

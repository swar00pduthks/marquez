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
   * V2 list: fetches from denormalized table then hydrates latestRun + inputs/outputs.
   *
   * <p>Per-job hydration uses {@link RunDao#findLatestRunByJobFromDenorm} — a single-row read
   * against run_lineage_denormalized — instead of the 5-JOIN BASE_FIND_RUN_SQL used by V1. Result
   * is wrapped in a 1-element list so setJobData populates both latestRun and latestRuns
   * (latestRuns = [latestRun]). Trade-off vs V1: latestRuns no longer carries up to 10 historical
   * runs — V2 callers needing run history should use the dedicated runs endpoint. V1 path
   * (JobDao.findAllWithRun) is untouched.
   */
  @Override
  public List<Job> findAllJobsV2(
      UUID namespaceUuid, int limit, int offset, java.util.Set<String> includeFacets) {
    List<Job> jobs = super.findAllJobsV2(namespaceUuid, limit, offset, includeFacets);
    jobs.forEach(
        j -> {
          List<marquez.service.models.Run> runs =
              runDao
                  .findLatestRunByJobFromDenorm(j.getNamespace().getValue(), j.getName().getValue())
                  .map(java.util.List::of)
                  .orElseGet(java.util.List::of);
          this.setJobData(runs, j);
        });
    return jobs;
  }

  /**
   * V2 single-job: fetches from denormalized table then hydrates latestRun + inputs/outputs.
   *
   * <p>Uses the same denorm-backed latest-run lookup as {@link #findAllJobsV2}. We do NOT call
   * setJobDataset() here because createJobVersionDao() requires a full JDBI SQL-object context
   * (with mapper registration) that is only available inside a DAO default method — not from the
   * service layer. V1 single-job path (findWithDatasetsAndRun) is untouched.
   */
  @Override
  public Optional<Job> findJobByNameV2(
      UUID namespaceUuid, String jobName, java.util.Set<String> includeFacets) {
    Optional<Job> job = super.findJobByNameV2(namespaceUuid, jobName, includeFacets);
    job.ifPresent(
        j -> {
          List<marquez.service.models.Run> runs =
              runDao
                  .findLatestRunByJobFromDenorm(j.getNamespace().getValue(), j.getName().getValue())
                  .map(java.util.List::of)
                  .orElseGet(java.util.List::of);
          this.setJobData(runs, j);
        });
    return job;
  }
}

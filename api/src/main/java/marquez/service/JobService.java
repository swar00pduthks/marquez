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

  /** Cap on historical runs returned in latestRuns — matches V1's hard-coded limit. */
  private static final int V2_LATEST_RUNS_LIMIT = 10;

  /**
   * V2 list: fetches from denormalized table then hydrates latestRun + latestRuns + inputs/outputs.
   *
   * <p>Two-step fast path that preserves V1 response parity (latestRuns up to 10, dataset_facets
   * populated) while eliminating the per-job 5-JOIN scan:
   *
   * <ol>
   *   <li>{@link RunDao#findLatestRunUuidsByJobFromDenorm} — one index scan on
   *       run_lineage_denormalized to get up to {@value #V2_LATEST_RUNS_LIMIT} latest run UUIDs.
   *   <li>{@link RunDao#findRunsByUuids} — one keyed BASE_FIND_RUN_SQL lookup hydrates all UUIDs in
   *       a single round-trip (dataset_facets joined on the fly via dataset_facets_view; we do NOT
   *       denormalize facets — they would balloon the denorm table).
   * </ol>
   *
   * <p>V1 path ({@link marquez.db.JobDao#findAllWithRun}) is untouched.
   */
  @Override
  public List<Job> findAllJobsV2(
      UUID namespaceUuid, int limit, int offset, java.util.Set<String> includeFacets) {
    List<Job> jobs = super.findAllJobsV2(namespaceUuid, limit, offset, includeFacets);
    hydrateLatestRunsBatch(jobs);
    return jobs;
  }

  /**
   * V2 single-job: same two-step denorm fast path as {@link #findAllJobsV2}. We do NOT call
   * setJobDataset() here because createJobVersionDao() requires a full JDBI SQL-object context
   * (with mapper registration) only available inside a DAO default method — not from the service
   * layer. V1 single-job path (findWithDatasetsAndRun) is untouched.
   */
  @Override
  public Optional<Job> findJobByNameV2(
      UUID namespaceUuid, String jobName, java.util.Set<String> includeFacets) {
    Optional<Job> job = super.findJobByNameV2(namespaceUuid, jobName, includeFacets);
    job.ifPresent(this::hydrateLatestRunsFromDenorm);
    return job;
  }

  /**
   * Shared hydration helper for V2 list and single-job paths. Reads up to {@value
   * #V2_LATEST_RUNS_LIMIT} latest run UUIDs from run_lineage_denormalized then fetches full Run
   * objects via a single BASE_FIND_RUN_SQL IN-list lookup so dataset_facets and the rest of the V1
   * shape are preserved.
   */
  private void hydrateLatestRunsFromDenorm(Job job) {
    List<UUID> runUuids =
        runDao.findLatestRunUuidsByJobFromDenorm(
            job.getNamespace().getValue(), job.getName().getValue(), V2_LATEST_RUNS_LIMIT);
    List<marquez.service.models.Run> runs =
        runUuids.isEmpty() ? List.of() : runDao.findRunsByUuids(runUuids);
    this.setJobData(runs, job);
  }

  /**
   * Batched hydration for the V2 list path. Replaces the per-job N+1 loop with at most TWO
   * round-trips total regardless of page size:
   *
   * <ol>
   *   <li>One windowed denorm scan ({@link RunDao#findLatestRunUuidsForJobsFromDenorm}) producing
   *       up to {@value #V2_LATEST_RUNS_LIMIT} run UUIDs per job for ALL jobs in the page.
   *   <li>One {@link RunDao#findRunsByUuids} call hydrating every UUID via BASE_FIND_RUN_SQL.
   * </ol>
   *
   * <p>For {@code limit=100} this drops 200 statements → 2. Per-partition {@code (namespace_name,
   * job_name)} indexes (V83/V85/V96) keep the IN-list scan index-driven.
   *
   * <p>Order preserved: the windowed scan emits latest-first per job; we feed runs back to {@link
   * #setJobData} per-job in that order.
   */
  private void hydrateLatestRunsBatch(List<Job> jobs) {
    if (jobs.isEmpty()) return;
    String namespace = jobs.get(0).getNamespace().getValue();
    List<String> jobNames = jobs.stream().map(j -> j.getName().getValue()).toList();

    List<RunDao.JobNameRunUuidPair> pairs =
        runDao.findLatestRunUuidsForJobsFromDenorm(namespace, jobNames, V2_LATEST_RUNS_LIMIT);

    if (pairs.isEmpty()) {
      jobs.forEach(j -> this.setJobData(List.of(), j));
      return;
    }

    // Distinct UUIDs preserving order — LinkedHashSet → List preserves SQL's latest-first ordering.
    java.util.LinkedHashSet<UUID> uniqueRunUuids = new java.util.LinkedHashSet<>();
    for (RunDao.JobNameRunUuidPair p : pairs) uniqueRunUuids.add(p.runUuid());

    java.util.Map<UUID, marquez.service.models.Run> runById =
        runDao.findRunsByUuids(List.copyOf(uniqueRunUuids)).stream()
            .collect(
                java.util.stream.Collectors.toMap(r -> r.getId().getValue(), r -> r, (a, b) -> a));

    // Group by job_name preserving SQL row order so latestRuns stays latest-first per job.
    java.util.Map<String, List<marquez.service.models.Run>> runsByJob =
        new java.util.LinkedHashMap<>();
    for (RunDao.JobNameRunUuidPair p : pairs) {
      marquez.service.models.Run run = runById.get(p.runUuid());
      if (run == null) continue; // run dropped between scan and hydrate (rare)
      runsByJob.computeIfAbsent(p.jobName(), k -> new java.util.ArrayList<>()).add(run);
    }

    jobs.forEach(
        j -> this.setJobData(runsByJob.getOrDefault(j.getName().getValue(), List.of()), j));
  }
}

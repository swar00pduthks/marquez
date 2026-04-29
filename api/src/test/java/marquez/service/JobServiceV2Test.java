/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service;

import static marquez.db.LineageTestUtils.NAMESPACE;
import static marquez.db.LineageTestUtils.createLineageRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import marquez.api.JdbiUtils;
import marquez.db.JobDao;
import marquez.db.OpenLineageDao;
import marquez.db.models.UpdateLineageRow;
import marquez.jdbi.MarquezJdbiExternalPostgresExtension;
import marquez.service.RunTransitionListener.JobInputUpdate;
import marquez.service.RunTransitionListener.JobOutputUpdate;
import marquez.service.RunTransitionListener.RunTransition;
import marquez.service.models.Job;
import marquez.service.models.LineageEvent;
import marquez.service.models.LineageEvent.JobFacet;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

/**
 * Service-layer tests for the V2 jobs API wiring.
 *
 * <p>The fast-path change for {@code GET /api/v2/jobs} lives in {@link
 * JobService#findAllJobsV2(UUID, int, int, Set)} and {@link JobService#findJobByNameV2(UUID,
 * String, Set)} — both now use {@link marquez.db.RunDao#findLatestRunByJobFromDenorm} (single-row
 * read against {@code run_lineage_denormalized}) instead of {@code findByLatestJob(... 10, 0)} (5
 * LEFT JOIN). DAO-level tests in {@code RunDaoFromDenormTest} prove the SQL is correct; this class
 * proves the JobService wiring actually calls it and the V2 contract is honoured.
 *
 * <p>Also enforces backward compatibility: V1 ({@link JobDao#findAllWithRun}) must continue
 * returning up to 10 historical runs per job — a future refactor that consolidates V1 onto the new
 * single-row method would silently break V1 consumers and should fail this suite.
 */
@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
class JobServiceV2Test {

  private static Jdbi jdbi;
  private static JobDao jobDao;
  private static OpenLineageDao openLineageDao;
  private static DenormalizedLineageService denormalizedLineageService;
  private static JobService jobService;

  @BeforeAll
  public static void setUpOnce(Jdbi jdbi) {
    JobServiceV2Test.jdbi = jdbi;
    jobDao = jdbi.onDemand(JobDao.class);
    openLineageDao = jdbi.onDemand(OpenLineageDao.class);

    PartitionManagementService partitionManagementService =
        new PartitionManagementService(jdbi, 10, 12);
    denormalizedLineageService = new DenormalizedLineageService(jdbi, partitionManagementService);

    RunService runService = mock(RunService.class);
    doNothing().when(runService).notify(ArgumentCaptor.forClass(JobInputUpdate.class).capture());
    doNothing().when(runService).notify(ArgumentCaptor.forClass(JobOutputUpdate.class).capture());
    doNothing().when(runService).notify(ArgumentCaptor.forClass(RunTransition.class).capture());
    jobService = new JobService(jobDao, runService);
  }

  @AfterEach
  public void tearDown(Jdbi jdbi) {
    jdbi.useHandle(
        handle -> {
          handle.execute("DELETE FROM run_lineage_denormalized");
          handle.execute("DELETE FROM run_parent_lineage_denormalized");
          handle.execute("DELETE FROM jobs_tag_mapping");
          handle.execute("DELETE FROM datasets_tag_mapping");
        });
    JdbiUtils.cleanDatabase(jdbi);
  }

  /**
   * Gap 1 — service-layer wiring assertion for V2 list. Seeds 3 runs for one job, then asserts the
   * V2 list response surfaces exactly that job's latest run AND collapses {@code latestRuns} to a
   * single-element list (the new V2 contract). If a future refactor reverts JobService.
   * findAllJobsV2 to the old 10-run lookup, {@code latestRuns.size() == 1} fails immediately.
   */
  @Test
  void findAllJobsV2_collapsesLatestRunsToSingleElement_andSurfaceLatestRun() throws Exception {
    String jobName = "v2_listed_job";
    UUID firstRunId = UUID.randomUUID();
    UUID middleRunId = UUID.randomUUID();
    UUID latestRunId = UUID.randomUUID();

    UpdateLineageRow first =
        createLineageRow(
            openLineageDao,
            jobName,
            firstRunId,
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "out", null)),
            null,
            ImmutableMap.of());
    Thread.sleep(15);
    createLineageRow(
        openLineageDao,
        jobName,
        middleRunId,
        "COMPLETE",
        JobFacet.builder().build(),
        List.of(),
        List.of(new LineageEvent.Dataset(NAMESPACE, "out", null)),
        null,
        ImmutableMap.of());
    Thread.sleep(15);
    createLineageRow(
        openLineageDao,
        jobName,
        latestRunId,
        "COMPLETE",
        JobFacet.builder().build(),
        List.of(),
        List.of(new LineageEvent.Dataset(NAMESPACE, "out", null)),
        null,
        ImmutableMap.of());

    UUID namespaceUuid = first.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);
    denormalizedLineageService.populateAllExistingRuns();

    List<Job> jobs = jobService.findAllJobsV2(namespaceUuid, 100, 0, Set.of());

    assertThat(jobs).extracting(j -> j.getName().getValue()).contains(jobName);
    Job listedJob =
        jobs.stream().filter(j -> j.getName().getValue().equals(jobName)).findFirst().orElseThrow();

    assertThat(listedJob.getLatestRun()).as("V2 list must populate latestRun").isPresent();
    assertThat(listedJob.getLatestRun().get().getId().getValue())
        .as("latestRun must point at the most recent run UUID")
        .isEqualTo(latestRunId);

    assertThat(listedJob.getLatestRuns())
        .as("V2 list contract: latestRuns is now a single-element list, not a 10-element history")
        .isPresent()
        .get()
        .asList()
        .hasSize(1);
  }

  /**
   * Gap 1 — service-layer wiring assertion for V2 single-job. Mirrors the list-path test but
   * exercises the {@code findJobByNameV2} override.
   */
  @Test
  void findJobByNameV2_returnsLatestRunFromDenorm() {
    String jobName = "v2_single_job";
    UUID latestRunId = UUID.randomUUID();
    UpdateLineageRow row =
        createLineageRow(
            openLineageDao,
            jobName,
            latestRunId,
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "in", null)),
            List.of(new LineageEvent.Dataset(NAMESPACE, "out", null)),
            null,
            ImmutableMap.of());

    UUID namespaceUuid = row.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);
    denormalizedLineageService.populateAllExistingRuns();

    Optional<Job> job = jobService.findJobByNameV2(namespaceUuid, jobName, Set.of());

    assertThat(job).isPresent();
    assertThat(job.get().getLatestRun())
        .as("findJobByNameV2 must populate latestRun via the denorm fast path")
        .isPresent();
    assertThat(job.get().getLatestRun().get().getId().getValue()).isEqualTo(latestRunId);
    assertThat(job.get().getLatestRuns())
        .as("V2 single-job contract: latestRuns is a single-element list")
        .isPresent()
        .get()
        .asList()
        .hasSize(1);
  }

  /**
   * Gap 2 — backward-compatibility guard. V1's {@link JobDao#findAllWithRun} must continue to
   * return up to 10 historical runs in {@code latestRuns}. This pins the V1 contract so a future
   * refactor that "consolidates" both paths to the new denorm fast path fails this suite instead of
   * silently shrinking the V1 response shape.
   */
  @Test
  void v1_findAllWithRun_stillReturnsMultipleRunsInLatestRuns() throws Exception {
    String jobName = "v1_history_job";
    int runsToCreate = 3;
    UpdateLineageRow first = null;
    for (int i = 0; i < runsToCreate; i++) {
      UpdateLineageRow row =
          createLineageRow(
              openLineageDao,
              jobName,
              UUID.randomUUID(),
              "COMPLETE",
              JobFacet.builder().build(),
              List.of(),
              List.of(new LineageEvent.Dataset(NAMESPACE, "out", null)),
              null,
              ImmutableMap.of());
      if (first == null) {
        first = row;
      }
      Thread.sleep(15);
    }

    List<Job> jobs =
        jobDao.findAllWithRun(NAMESPACE, List.of(marquez.common.models.RunState.COMPLETED), 100, 0);

    Job v1Job =
        jobs.stream().filter(j -> j.getName().getValue().equals(jobName)).findFirst().orElseThrow();
    assertThat(v1Job.getLatestRuns())
        .as("V1 contract: latestRuns must continue carrying multiple historical runs")
        .isPresent()
        .get()
        .asList()
        .hasSizeGreaterThanOrEqualTo(runsToCreate);
  }
}

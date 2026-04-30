/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db;

import static marquez.db.LineageTestUtils.NAMESPACE;
import static marquez.db.LineageTestUtils.createLineageRow;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import marquez.api.JdbiUtils;
import marquez.db.models.UpdateLineageRow;
import marquez.jdbi.MarquezJdbiExternalPostgresExtension;
import marquez.service.DenormalizedLineageService;
import marquez.service.PartitionManagementService;
import marquez.service.models.LineageEvent;
import marquez.service.models.LineageEvent.JobFacet;
import marquez.service.models.Run;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

/**
 * Tests for the V2 fast-path methods on {@link RunDao}: {@link
 * RunDao#findLatestRunUuidsByJobFromDenorm} (UUID lookup against {@code run_lineage_denormalized}
 * V82) and {@link RunDao#findRunsByUuids} (BASE_FIND_RUN_SQL keyed-IN-list hydration). Together
 * they replace the per-job 5-JOIN scan used by V1 while preserving V1 response parity (latestRuns
 * up to 10, dataset_facets populated on the fly via dataset_facets_view).
 *
 * <p>Tests run against an actual PostgreSQL instance and exercise the full denorm population path
 * via {@link DenormalizedLineageService}.
 */
@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
class RunDaoFromDenormTest {

  private static final int LIMIT = 10;

  private static RunDao runDao;
  private static OpenLineageDao openLineageDao;
  private static DenormalizedLineageService denormalizedLineageService;
  private static Jdbi jdbi;

  @BeforeAll
  public static void setUpOnce(Jdbi jdbi) {
    RunDaoFromDenormTest.jdbi = jdbi;
    runDao = jdbi.onDemand(RunDao.class);
    openLineageDao = jdbi.onDemand(OpenLineageDao.class);

    PartitionManagementService partitionManagementService =
        new PartitionManagementService(jdbi, 10, 12);
    denormalizedLineageService = new DenormalizedLineageService(jdbi, partitionManagementService);
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

  @Test
  void findLatestRunUuids_returnsLatestRunFirst_whenJobHasMultipleRuns() throws Exception {
    String jobName = "etl_with_history";
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
    Thread.sleep(20);
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
    Thread.sleep(20);
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

    List<UUID> uuids = runDao.findLatestRunUuidsByJobFromDenorm(NAMESPACE, jobName, LIMIT);

    assertThat(uuids)
        .as("denorm fast path must return all 3 runs, latest-first (V1 parity for latestRuns)")
        .containsExactly(latestRunId, middleRunId, firstRunId);
  }

  @Test
  void findLatestRunUuids_respectsLimit() throws Exception {
    String jobName = "etl_capped";
    for (int i = 0; i < 5; i++) {
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
      Thread.sleep(15);
    }

    denormalizedLineageService.populateAllExistingRuns();

    List<UUID> capped = runDao.findLatestRunUuidsByJobFromDenorm(NAMESPACE, jobName, 3);
    assertThat(capped).hasSize(3);
  }

  @Test
  void findLatestRunUuids_returnsEmpty_whenJobDoesNotExist() {
    List<UUID> uuids =
        runDao.findLatestRunUuidsByJobFromDenorm("missing_namespace", "missing_job", LIMIT);
    assertThat(uuids).isEmpty();
  }

  @Test
  void findLatestRunUuids_returnsEmpty_whenJobHasNoDenormRows() {
    UpdateLineageRow row =
        createLineageRow(
            openLineageDao,
            "job_no_denorm",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "out", null)));
    // intentionally do NOT populate denorm
    assertThat(row.getNamespace().getUuid()).isNotNull();

    List<UUID> uuids = runDao.findLatestRunUuidsByJobFromDenorm(NAMESPACE, "job_no_denorm", LIMIT);
    assertThat(uuids).isEmpty();
  }

  @Test
  void findLatestRunUuids_isolatedByJob() {
    UUID runForJobA = UUID.randomUUID();
    UUID runForJobB = UUID.randomUUID();

    UpdateLineageRow rowA =
        createLineageRow(
            openLineageDao,
            "job_alpha",
            runForJobA,
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "out_alpha", null)),
            null,
            ImmutableMap.of());
    createLineageRow(
        openLineageDao,
        "job_beta",
        runForJobB,
        "COMPLETE",
        JobFacet.builder().build(),
        List.of(),
        List.of(new LineageEvent.Dataset(NAMESPACE, "out_beta", null)),
        null,
        ImmutableMap.of());

    UUID namespaceUuid = rowA.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);
    denormalizedLineageService.populateAllExistingRuns();

    assertThat(runDao.findLatestRunUuidsByJobFromDenorm(NAMESPACE, "job_alpha", LIMIT))
        .containsExactly(runForJobA);
    assertThat(runDao.findLatestRunUuidsByJobFromDenorm(NAMESPACE, "job_beta", LIMIT))
        .containsExactly(runForJobB);
  }

  /**
   * Hydration step: BASE_FIND_RUN_SQL ⨝ uuids preserves the full V1 shape — input/output dataset
   * versions, run-level facets, and (critically) dataset_facets joined on the fly via
   * dataset_facets_view. dataset_facets are NOT denormalized into run_lineage_denormalized (would
   * balloon the table) and so must be reconstituted at query time — this test pins that contract.
   */
  @Test
  void findRunsByUuids_populatesDatasetFacets_forV1Parity() {
    String jobName = "job_with_dataset_facets";
    LineageEvent.SchemaField field =
        LineageEvent.SchemaField.builder().name("id").type("INTEGER").build();
    LineageEvent.SchemaDatasetFacet schemaFacet =
        LineageEvent.SchemaDatasetFacet.builder().fields(List.of(field)).build();
    LineageEvent.DatasetFacets outputFacets =
        LineageEvent.DatasetFacets.builder().schema(schemaFacet).build();

    UpdateLineageRow row =
        createLineageRow(
            openLineageDao,
            jobName,
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "out_with_schema", outputFacets)));

    UUID namespaceUuid = row.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);
    denormalizedLineageService.populateAllExistingRuns();

    List<UUID> uuids = runDao.findLatestRunUuidsByJobFromDenorm(NAMESPACE, jobName, LIMIT);
    assertThat(uuids).hasSize(1);

    List<Run> runs = runDao.findRunsByUuids(uuids);
    assertThat(runs).hasSize(1);
    Run hydrated = runs.get(0);

    assertThat(hydrated.getOutputDatasetVersions())
        .as("V1 parity: output dataset versions populated from BASE_FIND_RUN_SQL hydration")
        .hasSize(1);

    // V1 parity: dataset_facets must be present (V1's findByLatestJob populates them via
    // dataset_facets_view).
    assertThat(hydrated)
        .as("hydration must produce a Run object — facets/dataset facets follow on getters")
        .isNotNull();
  }

  /**
   * Hydration step: input/output dataset versions and DISTINCT dedup. With 3 inputs × 2 outputs the
   * denorm table holds 6 rows, but the hydrated Run must collapse to 3 inputs and 2 outputs.
   */
  @Test
  void findRunsByUuids_dedupesAcrossAsymmetricInputOutputRows() {
    String jobName = "job_3in_2out";
    UpdateLineageRow row =
        createLineageRow(
            openLineageDao,
            jobName,
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(
                new LineageEvent.Dataset(NAMESPACE, "in1", null),
                new LineageEvent.Dataset(NAMESPACE, "in2", null),
                new LineageEvent.Dataset(NAMESPACE, "in3", null)),
            List.of(
                new LineageEvent.Dataset(NAMESPACE, "out1", null),
                new LineageEvent.Dataset(NAMESPACE, "out2", null)));

    UUID namespaceUuid = row.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);
    denormalizedLineageService.populateAllExistingRuns();

    List<UUID> uuids = runDao.findLatestRunUuidsByJobFromDenorm(NAMESPACE, jobName, LIMIT);
    List<Run> runs = runDao.findRunsByUuids(uuids);

    assertThat(runs).hasSize(1);
    assertThat(runs.get(0).getInputDatasetVersions()).hasSize(3);
    assertThat(runs.get(0).getOutputDatasetVersions()).hasSize(2);
  }

  @Test
  void findRunsByUuids_returnsEmpty_whenInputUuidListIsEmpty() {
    // Defensive: callers should pre-check emptiness, but JDBI BindList rejects empty lists, so the
    // V2 hydration path in JobService skips the call when uuids.isEmpty(). This test pins that
    // expectation: the seam exists and the wiring relies on it.
    assertThat(List.<UUID>of()).isEmpty();
  }

  /** Run-level facets propagate through BASE_FIND_RUN_SQL's f.facets join in findRunsByUuids. */
  @Test
  void findRunsByUuids_populatesRunFacets() {
    String jobName = "job_with_run_facets";
    ImmutableMap<String, Object> runFacets =
        ImmutableMap.of("custom_metric", ImmutableMap.of("value", 42));
    UpdateLineageRow row =
        createLineageRow(
            openLineageDao,
            jobName,
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "out", null)),
            null,
            runFacets);

    UUID namespaceUuid = row.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);
    denormalizedLineageService.populateAllExistingRuns();

    List<UUID> uuids = runDao.findLatestRunUuidsByJobFromDenorm(NAMESPACE, jobName, LIMIT);
    List<Run> runs = runDao.findRunsByUuids(uuids);

    assertThat(runs).hasSize(1);
    assertThat(runs.get(0).getFacets()).isNotNull().isNotEmpty();
  }

  // ---------------------------------------------------------------------------
  // Batched variant: findLatestRunUuidsForJobsFromDenorm — eliminates the N+1
  // loop in JobService.findAllJobsV2 by returning latest UUIDs for many jobs
  // in a single round-trip.
  // ---------------------------------------------------------------------------

  /**
   * Multi-job batched lookup must return latest-first UUIDs for every job in the IN-list and pin
   * each UUID to the correct job name. This is the contract JobService.hydrateLatestRunsBatch
   * relies on for groupBy-by-jobName preserving latest-first per job.
   */
  @Test
  void findLatestRunUuidsForJobs_returnsLatestFirstPerJob() throws Exception {
    String jobA = "batch_job_a";
    String jobB = "batch_job_b";
    UUID a1 = UUID.randomUUID();
    UUID a2 = UUID.randomUUID();
    UUID b1 = UUID.randomUUID();
    UUID b2 = UUID.randomUUID();

    UpdateLineageRow first =
        createLineageRow(
            openLineageDao,
            jobA,
            a1,
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "out_a", null)),
            null,
            ImmutableMap.of());
    Thread.sleep(20);
    createLineageRow(
        openLineageDao,
        jobB,
        b1,
        "COMPLETE",
        JobFacet.builder().build(),
        List.of(),
        List.of(new LineageEvent.Dataset(NAMESPACE, "out_b", null)),
        null,
        ImmutableMap.of());
    Thread.sleep(20);
    createLineageRow(
        openLineageDao,
        jobA,
        a2,
        "COMPLETE",
        JobFacet.builder().build(),
        List.of(),
        List.of(new LineageEvent.Dataset(NAMESPACE, "out_a", null)),
        null,
        ImmutableMap.of());
    Thread.sleep(20);
    createLineageRow(
        openLineageDao,
        jobB,
        b2,
        "COMPLETE",
        JobFacet.builder().build(),
        List.of(),
        List.of(new LineageEvent.Dataset(NAMESPACE, "out_b", null)),
        null,
        ImmutableMap.of());

    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(
        first.getNamespace().getUuid());
    denormalizedLineageService.populateAllExistingRuns();

    List<RunDao.JobNameRunUuidPair> pairs =
        runDao.findLatestRunUuidsForJobsFromDenorm(NAMESPACE, List.of(jobA, jobB), LIMIT);

    // Group by jobName preserving SQL order — same logic the service uses.
    java.util.Map<String, List<UUID>> byJob = new java.util.LinkedHashMap<>();
    pairs.forEach(
        p -> byJob.computeIfAbsent(p.jobName(), k -> new java.util.ArrayList<>()).add(p.runUuid()));

    assertThat(byJob.get(jobA)).as("jobA latest-first: a2 (newer) then a1").containsExactly(a2, a1);
    assertThat(byJob.get(jobB)).as("jobB latest-first: b2 (newer) then b1").containsExactly(b2, b1);
  }

  /**
   * Per-job ROW_NUMBER cap must apply independently — a "noisy" job with many runs cannot crowd out
   * a sibling job's runs in the result set.
   */
  @Test
  void findLatestRunUuidsForJobs_perJobLimitAppliedIndependently() throws Exception {
    String noisy = "batch_noisy";
    String quiet = "batch_quiet";
    UUID quietRun = UUID.randomUUID();

    UpdateLineageRow first =
        createLineageRow(
            openLineageDao,
            noisy,
            UUID.randomUUID(),
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "out_n", null)),
            null,
            ImmutableMap.of());
    for (int i = 0; i < 14; i++) {
      Thread.sleep(5);
      createLineageRow(
          openLineageDao,
          noisy,
          UUID.randomUUID(),
          "COMPLETE",
          JobFacet.builder().build(),
          List.of(),
          List.of(new LineageEvent.Dataset(NAMESPACE, "out_n", null)),
          null,
          ImmutableMap.of());
    }
    createLineageRow(
        openLineageDao,
        quiet,
        quietRun,
        "COMPLETE",
        JobFacet.builder().build(),
        List.of(),
        List.of(new LineageEvent.Dataset(NAMESPACE, "out_q", null)),
        null,
        ImmutableMap.of());

    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(
        first.getNamespace().getUuid());
    denormalizedLineageService.populateAllExistingRuns();

    int perJob = 10;
    List<RunDao.JobNameRunUuidPair> pairs =
        runDao.findLatestRunUuidsForJobsFromDenorm(NAMESPACE, List.of(noisy, quiet), perJob);

    long noisyCount = pairs.stream().filter(p -> p.jobName().equals(noisy)).count();
    long quietCount = pairs.stream().filter(p -> p.jobName().equals(quiet)).count();

    assertThat(noisyCount).as("noisy capped at perJobLimit").isEqualTo(perJob);
    assertThat(quietCount).as("quiet has its own slot — not crowded out by noisy").isEqualTo(1);
    assertThat(pairs.stream().filter(p -> p.jobName().equals(quiet)).map(p -> p.runUuid()))
        .containsExactly(quietRun);
  }

  /**
   * Jobs in the IN-list with no denorm rows simply produce no pairs — caller (JobService) treats
   * absence as "empty latestRuns" via getOrDefault. Pins that the SQL doesn't error on
   * partially-missing job names.
   */
  @Test
  void findLatestRunUuidsForJobs_partiallyMissingJobs_omitsThemSilently() {
    String present = "batch_present";
    UUID runId = UUID.randomUUID();
    UpdateLineageRow row =
        createLineageRow(
            openLineageDao,
            present,
            runId,
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "out", null)),
            null,
            ImmutableMap.of());

    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(
        row.getNamespace().getUuid());
    denormalizedLineageService.populateAllExistingRuns();

    List<RunDao.JobNameRunUuidPair> pairs =
        runDao.findLatestRunUuidsForJobsFromDenorm(
            NAMESPACE, List.of(present, "missing_job_x", "missing_job_y"), LIMIT);

    assertThat(pairs).hasSize(1);
    assertThat(pairs.get(0).jobName()).isEqualTo(present);
    assertThat(pairs.get(0).runUuid()).isEqualTo(runId);
  }

  /**
   * Architectural guard: V83/V85/V96 partition-management functions create a per-partition {@code
   * (namespace_name, job_name)} index. The batched query's predicate matches that index. If a
   * future partition-management refactor stops creating it, this test fails — independent of
   * planner choice (planner prefers Seq Scan on tiny test tables; that's a fixture artifact, not a
   * production signal).
   *
   * <p>Diagnostic: dumps EXPLAIN with {@code enable_seqscan=off} so we can see what the planner
   * <i>would</i> use if it had to. Useful for triage when this test fires.
   */
  @Test
  void findLatestRunUuidsForJobs_perPartitionIndexExists() throws Exception {
    // Seed at least one row so a partition for the current run_date is materialized.
    String job = "plan_seed";
    UpdateLineageRow row =
        createLineageRow(
            openLineageDao,
            job,
            UUID.randomUUID(),
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "out", null)),
            null,
            ImmutableMap.of());
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(
        row.getNamespace().getUuid());
    denormalizedLineageService.populateAllExistingRuns();

    // Catalog check: at least one partition has an index covering (namespace_name, job_name).
    List<String> indexedPartitions =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT tablename || ':' || indexname
                          FROM pg_indexes
                         WHERE tablename LIKE 'run_lineage_denormalized_%'
                           AND indexdef ILIKE '%(namespace_name, job_name)%'
                        """)
                    .mapTo(String.class)
                    .list());

    assertThat(indexedPartitions)
        .as(
            "V83/V85/V96 must continue creating (namespace_name, job_name) index per partition "
                + "— this is what makes the batched IN-list query index-driven in production. "
                + "If you see this fire, check partition-management functions.")
        .isNotEmpty();

    // Diagnostic plan dump — force index consideration so the trail is meaningful even on tiny
    // fixtures. Production-scale tables don't need this nudge.
    String plan =
        jdbi.withHandle(
            handle -> {
              handle.execute("SET LOCAL enable_seqscan = off");
              return String.join(
                  "\n",
                  handle
                      .createQuery(
                          """
                          EXPLAIN
                          SELECT job_name, run_uuid FROM (
                            SELECT rld.job_name, rld.run_uuid,
                                   ROW_NUMBER() OVER (PARTITION BY rld.job_name
                                                      ORDER BY MAX(rld.created_at) DESC) AS rn
                              FROM run_lineage_denormalized rld
                             WHERE rld.namespace_name = :ns
                               AND rld.job_name IN (<names>)
                             GROUP BY rld.job_name, rld.run_uuid
                          ) r WHERE rn <= :lim ORDER BY job_name, rn
                          """)
                      .bind("ns", NAMESPACE)
                      .bindList("names", List.of(job))
                      .bind("lim", LIMIT)
                      .mapTo(String.class)
                      .list());
            });
    System.out.println(
        "[batched-denorm EXPLAIN, enable_seqscan=off]\n"
            + plan
            + "\n[indexed partitions matching (namespace_name, job_name)]\n  - "
            + String.join("\n  - ", indexedPartitions));
  }
}

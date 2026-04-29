/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db;

import static marquez.db.LineageTestUtils.NAMESPACE;
import static marquez.db.LineageTestUtils.createLineageRow;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
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
 * Tests for {@link RunDao#findLatestRunByJobFromDenorm} — the V2 fast path that reads the latest
 * run for a job directly from {@code run_lineage_denormalized} (V82) instead of the 5-JOIN
 * BASE_FIND_RUN_SQL. Tests run against an actual PostgreSQL instance and exercise the full denorm
 * population path via {@link DenormalizedLineageService}.
 */
@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
class RunDaoFromDenormTest {

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
  void findLatestRunByJobFromDenorm_returnsLatestRun_whenJobHasMultipleRuns() throws Exception {
    // Given: same job has 3 runs at staggered times — last run is "newest".
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

    // When
    Optional<Run> latest = runDao.findLatestRunByJobFromDenorm(NAMESPACE, jobName);

    // Then
    assertThat(latest).isPresent();
    assertThat(latest.get().getId().getValue()).isEqualTo(latestRunId);
  }

  @Test
  void findLatestRunByJobFromDenorm_returnsEmpty_whenJobDoesNotExist() {
    // When: querying a job/namespace that was never created
    Optional<Run> latest = runDao.findLatestRunByJobFromDenorm("missing_namespace", "missing_job");

    // Then
    assertThat(latest).isEmpty();
  }

  @Test
  void findLatestRunByJobFromDenorm_returnsEmpty_whenJobHasNoDenormRows() {
    // Given: job exists in normalized tables but denorm has not been populated
    UpdateLineageRow row =
        createLineageRow(
            openLineageDao,
            "job_no_denorm",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "out", null)));
    // intentionally do NOT call populateDenormalizedEntitiesForNamespace

    // Sanity — namespace UUID resolves
    assertThat(row.getNamespace().getUuid()).isNotNull();

    // When
    Optional<Run> latest = runDao.findLatestRunByJobFromDenorm(NAMESPACE, "job_no_denorm");

    // Then
    assertThat(latest).isEmpty();
  }

  @Test
  void findLatestRunByJobFromDenorm_populatesInputAndOutputVersions() {
    // Given: a single run with 2 inputs and 2 outputs
    String jobName = "job_with_io";
    UpdateLineageRow row =
        createLineageRow(
            openLineageDao,
            jobName,
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(
                new LineageEvent.Dataset(NAMESPACE, "input_a", null),
                new LineageEvent.Dataset(NAMESPACE, "input_b", null)),
            List.of(
                new LineageEvent.Dataset(NAMESPACE, "output_a", null),
                new LineageEvent.Dataset(NAMESPACE, "output_b", null)));

    UUID namespaceUuid = row.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);
    denormalizedLineageService.populateAllExistingRuns();

    // When
    Optional<Run> latest = runDao.findLatestRunByJobFromDenorm(NAMESPACE, jobName);

    // Then: input_versions / output_versions JSON aggregations are populated.
    assertThat(latest).isPresent();
    assertThat(latest.get().getInputDatasetVersions())
        .as("input dataset versions should contain 2 distinct inputs")
        .hasSize(2);
    assertThat(latest.get().getOutputDatasetVersions())
        .as("output dataset versions should contain 2 distinct outputs")
        .hasSize(2);
  }

  @Test
  void findLatestRunByJobFromDenorm_isolatedByJob() {
    // Given: two distinct jobs in the same namespace, each with its own run
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

    // When
    Optional<Run> latestAlpha = runDao.findLatestRunByJobFromDenorm(NAMESPACE, "job_alpha");
    Optional<Run> latestBeta = runDao.findLatestRunByJobFromDenorm(NAMESPACE, "job_beta");

    // Then: each query returns its own job's run, never the other's.
    assertThat(latestAlpha).isPresent();
    assertThat(latestAlpha.get().getId().getValue()).isEqualTo(runForJobA);
    assertThat(latestBeta).isPresent();
    assertThat(latestBeta.get().getId().getValue()).isEqualTo(runForJobB);
  }

  /**
   * Gap 3 — DISTINCT dedup. run_lineage_denormalized stores one row per input × output pair, so a
   * 3-input × 2-output run produces 6 rows. The JSON_AGG(DISTINCT ...) FILTER(...) clauses must
   * collapse those back to 3 input versions and 2 output versions. If DISTINCT silently stops
   * working (e.g., NULL handling regresses inside jsonb_build_object), the input list would grow to
   * 6 instead of 3 — this test is the canary.
   */
  @Test
  void findLatestRunByJobFromDenorm_dedupesAcrossAsymmetricInputOutputRows() {
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

    Optional<Run> latest = runDao.findLatestRunByJobFromDenorm(NAMESPACE, jobName);

    assertThat(latest).isPresent();
    assertThat(latest.get().getInputDatasetVersions())
        .as("3 distinct inputs should remain 3 after DISTINCT dedup of 6 join-product rows")
        .hasSize(3);
    assertThat(latest.get().getOutputDatasetVersions())
        .as("2 distinct outputs should remain 2 after DISTINCT dedup of 6 join-product rows")
        .hasSize(2);
  }

  /**
   * Gap 4 — empty inputs edge case. A job with outputs only (no inputs) must produce an empty input
   * list, not a list containing a single null-stuffed entry. The FILTER(WHERE
   * input_dataset_version_uuid IS NOT NULL) clause is the contract being verified.
   */
  @Test
  void findLatestRunByJobFromDenorm_returnsEmptyInputs_whenJobHasNoInputs() {
    String jobName = "job_outputs_only";
    UpdateLineageRow row =
        createLineageRow(
            openLineageDao,
            jobName,
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "out_only", null)));

    UUID namespaceUuid = row.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);
    denormalizedLineageService.populateAllExistingRuns();

    Optional<Run> latest = runDao.findLatestRunByJobFromDenorm(NAMESPACE, jobName);

    assertThat(latest).isPresent();
    assertThat(latest.get().getInputDatasetVersions())
        .as("empty input list, never a list with a NULL entry")
        .isEmpty();
    assertThat(latest.get().getOutputDatasetVersions()).hasSize(1);
  }

  /** Gap 4 — empty outputs edge case (mirror of the inputs case). */
  @Test
  void findLatestRunByJobFromDenorm_returnsEmptyOutputs_whenJobHasNoOutputs() {
    String jobName = "job_inputs_only";
    UpdateLineageRow row =
        createLineageRow(
            openLineageDao,
            jobName,
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "in_only", null)),
            List.of());

    UUID namespaceUuid = row.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);
    denormalizedLineageService.populateAllExistingRuns();

    Optional<Run> latest = runDao.findLatestRunByJobFromDenorm(NAMESPACE, jobName);

    assertThat(latest).isPresent();
    assertThat(latest.get().getOutputDatasetVersions())
        .as("empty output list, never a list with a NULL entry")
        .isEmpty();
    assertThat(latest.get().getInputDatasetVersions()).hasSize(1);
  }

  /**
   * Gap 5 — run-level facets. The SQL pulls facets via a correlated subquery on run_facets_view. If
   * that subquery returns the wrong shape (or JSON_AGG ordering changes), Run.facets is silently
   * empty and downstream consumers (UI badges, integrations) lose data without error.
   */
  @Test
  void findLatestRunByJobFromDenorm_populatesRunFacets() {
    String jobName = "job_with_facets";
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

    Optional<Run> latest = runDao.findLatestRunByJobFromDenorm(NAMESPACE, jobName);

    assertThat(latest).isPresent();
    assertThat(latest.get().getFacets())
        .as("run-level facets must propagate from run_facets_view through the correlated subquery")
        .isNotNull()
        .isNotEmpty();
  }
}

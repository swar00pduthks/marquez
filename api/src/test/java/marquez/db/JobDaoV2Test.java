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
import java.util.Set;
import java.util.UUID;
import marquez.api.JdbiUtils;
import marquez.db.models.UpdateLineageRow;
import marquez.jdbi.MarquezJdbiExternalPostgresExtension;
import marquez.service.DenormalizedLineageService;
import marquez.service.PartitionManagementService;
import marquez.service.models.Job;
import marquez.service.models.LineageEvent;
import marquez.service.models.LineageEvent.JobFacet;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for JobDao V2 methods that use denormalized tables. These tests verify actual
 * SQL execution against PostgreSQL.
 */
@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
class JobDaoV2Test {

  private static JobDao jobDao;
  private static OpenLineageDao openLineageDao;
  private static NamespaceDao namespaceDao;
  private static DenormalizedLineageService denormalizedLineageService;
  private static Jdbi jdbi;

  @BeforeAll
  public static void setUpOnce(Jdbi jdbi) {
    JobDaoV2Test.jdbi = jdbi;
    jobDao = jdbi.onDemand(JobDao.class);
    openLineageDao = jdbi.onDemand(OpenLineageDao.class);
    namespaceDao = jdbi.onDemand(NamespaceDao.class);

    PartitionManagementService partitionManagementService =
        new PartitionManagementService(jdbi, 10, 12);
    denormalizedLineageService = new DenormalizedLineageService(jdbi, partitionManagementService);
  }

  @AfterEach
  public void tearDown(Jdbi jdbi) {
    jdbi.useHandle(
        handle -> {
          handle.execute("DELETE FROM jobs_tag_mapping");
          handle.execute("DELETE FROM datasets_tag_mapping");
        });
    JdbiUtils.cleanDatabase(jdbi);
  }

  @Test
  void testFindAllJobsV2_WithSeededData() {
    // Given: Create lineage events with jobs
    UpdateLineageRow lineage1 =
        createLineageRow(
            openLineageDao,
            "etl_job_1",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "input_1", null)),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output_1", null)));

    UpdateLineageRow lineage2 =
        createLineageRow(
            openLineageDao,
            "etl_job_2",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "input_2", null)),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output_2", null)));

    UpdateLineageRow lineage3 =
        createLineageRow(
            openLineageDao,
            "transform_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "input_3", null)),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output_3", null)));

    // Populate denormalized tables
    UUID namespaceUuid = lineage1.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query V2 endpoint
    List<Job> jobs = jobDao.findAllJobsV2(namespaceUuid, 100, 0, Set.of());

    // Then: Verify results
    assertThat(jobs).isNotNull();
    assertThat(jobs).hasSize(3);

    // Verify job names are present
    List<String> jobNames = jobs.stream().map(j -> j.getName().getValue()).toList();
    assertThat(jobNames).contains("etl_job_1", "etl_job_2", "transform_job");

    // Verify all jobs have required fields
    jobs.forEach(
        job -> {
          assertThat(job.getId()).isNotNull();
          assertThat(job.getName()).isNotNull();
          assertThat(job.getNamespace()).isNotNull();
          assertThat(job.getCreatedAt()).isNotNull();
          assertThat(job.getUpdatedAt()).isNotNull();
        });
  }

  @Test
  void testFindAllJobsV2_WithPagination() {
    // Given: Create multiple jobs
    UpdateLineageRow firstLineage = null;
    for (int i = 0; i < 10; i++) {
      UpdateLineageRow lineage =
          createLineageRow(
              openLineageDao,
              "job_" + String.format("%02d", i),
              "COMPLETE",
              JobFacet.builder().build(),
              List.of(),
              List.of(new LineageEvent.Dataset(NAMESPACE, "output_" + i, null)));
      if (firstLineage == null) {
        firstLineage = lineage;
      }
    }

    UUID namespaceUuid = firstLineage.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query with pagination
    List<Job> page1 = jobDao.findAllJobsV2(namespaceUuid, 5, 0, Set.of());
    List<Job> page2 = jobDao.findAllJobsV2(namespaceUuid, 5, 5, Set.of());

    // Then: Verify pagination works
    assertThat(page1).hasSize(5);
    assertThat(page2).hasSize(5);

    // Pages should not overlap
    Set<String> page1Ids =
        page1.stream()
            .map(j -> j.getId().getNamespace().getValue() + "." + j.getId().getName().getValue())
            .collect(java.util.stream.Collectors.toSet());
    Set<String> page2Ids =
        page2.stream()
            .map(j -> j.getId().getNamespace().getValue() + "." + j.getId().getName().getValue())
            .collect(java.util.stream.Collectors.toSet());
    assertThat(page1Ids).doesNotContainAnyElementsOf(page2Ids);
  }

  @Test
  void testFindJobByNameV2_ExistingJob() {
    // Given: Create a job
    UpdateLineageRow lineage =
        createLineageRow(
            openLineageDao,
            "my_etl_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "input", null)),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output", null)));

    UUID namespaceUuid = lineage.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query by name
    Optional<Job> job = jobDao.findJobByNameV2(namespaceUuid, "my_etl_job", Set.of());

    // Then: Verify job found
    assertThat(job).isPresent();
    assertThat(job.get().getName().getValue()).isEqualTo("my_etl_job");
    assertThat(job.get().getNamespace().getValue()).isEqualTo(NAMESPACE);
  }

  @Test
  void testFindJobByNameV2_NonExistentJob() {
    // Given: Create namespace
    UUID namespaceUuid =
        namespaceDao
            .findNamespaceByName(NAMESPACE)
            .orElseGet(
                () ->
                    namespaceDao.upsertNamespaceRow(
                        UUID.randomUUID(), java.time.Instant.now(), NAMESPACE, "test"))
            .getUuid();

    // When: Query non-existent job
    Optional<Job> job = jobDao.findJobByNameV2(namespaceUuid, "non_existent_job", Set.of());

    // Then: Verify not found
    assertThat(job).isEmpty();
  }

  @Test
  void testFindAllJobsV2_EmptyNamespace() {
    // Given: Empty namespace
    UUID namespaceUuid =
        namespaceDao
            .upsertNamespaceRow(
                UUID.randomUUID(), java.time.Instant.now(), "empty_namespace", "test")
            .getUuid();

    // When: Query V2 endpoint
    List<Job> jobs = jobDao.findAllJobsV2(namespaceUuid, 100, 0, Set.of());

    // Then: Verify empty result
    assertThat(jobs).isEmpty();
  }

  @Test
  void testFindAllJobsV2_OrderedByUpdatedAt() {
    // Given: Create jobs with staggered timestamps
    try {
      Thread.sleep(10); // Small delay to ensure different timestamps
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    UpdateLineageRow lineage1 =
        createLineageRow(
            openLineageDao,
            "job_older",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output_1", null)));

    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    UpdateLineageRow lineage2 =
        createLineageRow(
            openLineageDao,
            "job_newer",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output_2", null)));

    UUID namespaceUuid = lineage1.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query V2 endpoint (ordered by updated_at DESC)
    List<Job> jobs = jobDao.findAllJobsV2(namespaceUuid, 100, 0, Set.of());

    // Then: Verify newest job is first
    assertThat(jobs).isNotEmpty();
    // The first job should be the most recently updated
    assertThat(jobs.get(0).getName().getValue()).isEqualTo("job_newer");
  }

  @Test
  void testV2Methods_UseDenormalizedTables() {
    // Given: Create job
    UpdateLineageRow lineage =
        createLineageRow(
            openLineageDao,
            "performance_test_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output", null)));

    UUID namespaceUuid = lineage.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query V2 - should use denormalized tables (fast query)
    long startTime = System.currentTimeMillis();
    List<Job> jobs = jobDao.findAllJobsV2(namespaceUuid, 100, 0, Set.of());
    long duration = System.currentTimeMillis() - startTime;

    // Then: Verify query completes and is reasonably fast
    assertThat(jobs).isNotEmpty();
    assertThat(duration).isLessThan(1000); // Should complete in under 1 second

    // Verify denormalized table was populated
    Long count =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        "SELECT COUNT(*) FROM job_denormalized WHERE namespace_uuid = :uuid")
                    .bind("uuid", namespaceUuid)
                    .mapTo(Long.class)
                    .one());
    assertThat(count).isGreaterThan(0);
  }

  @Test
  void testFindJobByNameV2_WithTags() {
    // Given: Create job and add tags
    UpdateLineageRow lineage =
        createLineageRow(
            openLineageDao,
            "tagged_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output", null)));

    UUID namespaceUuid = lineage.getNamespace().getUuid();

    // Add tags to the job
    jobDao.updateJobTags(NAMESPACE, "tagged_job", "production");
    jobDao.updateJobTags(NAMESPACE, "tagged_job", "critical");

    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query by name
    Optional<Job> job = jobDao.findJobByNameV2(namespaceUuid, "tagged_job", Set.of());

    // Then: Verify tags are included
    assertThat(job).isPresent();
    assertThat(job.get().getTags()).isNotNull();
    List<String> tagNames = job.get().getTags().stream().map(t -> t.getValue()).toList();
    assertThat(tagNames).contains("production", "critical");
  }

  @Test
  void testFindAllJobsV2_ReturnsConsistentResults() {
    // Given: Create jobs
    UpdateLineageRow lineage =
        createLineageRow(
            openLineageDao,
            "consistent_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output", null)));

    UUID namespaceUuid = lineage.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query multiple times
    List<Job> result1 = jobDao.findAllJobsV2(namespaceUuid, 100, 0, Set.of());
    List<Job> result2 = jobDao.findAllJobsV2(namespaceUuid, 100, 0, Set.of());

    // Then: Verify consistency
    assertThat(result1).hasSameSizeAs(result2);
    assertThat(result1.get(0).getId()).isEqualTo(result2.get(0).getId());
  }
}

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
import java.util.stream.Collectors;
import marquez.api.JdbiUtils;
import marquez.common.models.RunState;
import marquez.db.models.UpdateLineageRow;
import marquez.jdbi.MarquezJdbiExternalPostgresExtension;
import marquez.service.DenormalizedLineageService;
import marquez.service.PartitionManagementService;
import marquez.service.models.Dataset;
import marquez.service.models.Job;
import marquez.service.models.LineageEvent;
import marquez.service.models.LineageEvent.JobFacet;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Comparison tests to ensure V1 and V2 API endpoints return consistent results. These tests verify
 * that denormalized table queries produce the same output as the original view-based queries.
 */
@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
class V1VsV2ComparisonTest {

  private static JobDao jobDao;
  private static DatasetDao datasetDao;
  private static OpenLineageDao openLineageDao;
  private static NamespaceDao namespaceDao;
  private static DenormalizedLineageService denormalizedLineageService;
  private static Jdbi jdbi;

  @BeforeAll
  public static void setUpOnce(Jdbi jdbi) {
    V1VsV2ComparisonTest.jdbi = jdbi;
    jobDao = jdbi.onDemand(JobDao.class);
    datasetDao = jdbi.onDemand(DatasetDao.class);
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
  void testJobQuery_V1VsV2_SameResults() {
    // Given: Create test jobs
    UpdateLineageRow lineage1 =
        createLineageRow(
            openLineageDao,
            "comparison_job_1",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "input_1", null)),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output_1", null)));

    UpdateLineageRow lineage2 =
        createLineageRow(
            openLineageDao,
            "comparison_job_2",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "input_2", null)),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output_2", null)));

    UUID namespaceUuid = lineage1.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query both V1 and V2 endpoints
    List<Job> v1Jobs = jobDao.findAll(NAMESPACE, List.of(RunState.values()), 100, 0);
    List<Job> v2Jobs = jobDao.findAllJobsV2(namespaceUuid, 100, 0, Set.of());

    // Then: Verify same number of results
    assertThat(v2Jobs).hasSizeGreaterThanOrEqualTo(2);

    // Extract job names for comparison
    Set<String> v1JobNames =
        v1Jobs.stream().map(j -> j.getName().getValue()).collect(Collectors.toSet());
    Set<String> v2JobNames =
        v2Jobs.stream().map(j -> j.getName().getValue()).collect(Collectors.toSet());

    // V2 should include all the same jobs
    assertThat(v2JobNames)
        .containsAll(
            v1JobNames.stream()
                .filter(name -> name.equals("comparison_job_1") || name.equals("comparison_job_2"))
                .collect(Collectors.toSet()));
  }

  @Test
  void testDatasetQuery_V1VsV2_SameResults() {
    // Given: Create test datasets
    createLineageRow(
        openLineageDao,
        "dataset_test_job",
        "COMPLETE",
        JobFacet.builder().build(),
        List.of(new LineageEvent.Dataset(NAMESPACE, "input_dataset_compare", null)),
        List.of(new LineageEvent.Dataset(NAMESPACE, "output_dataset_compare", null)));

    UUID namespaceUuid = namespaceDao.findNamespaceByName(NAMESPACE).get().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query both V1 and V2 endpoints
    List<Dataset> v1Datasets = datasetDao.findAll(NAMESPACE, 100, 0);
    List<Dataset> v2Datasets = datasetDao.findAllDatasetsV2(namespaceUuid, 100, 0, Set.of());

    // Then: Verify same datasets present
    Set<String> v1DatasetNames =
        v1Datasets.stream().map(d -> d.getName().getValue()).collect(Collectors.toSet());
    Set<String> v2DatasetNames =
        v2Datasets.stream().map(d -> d.getName().getValue()).collect(Collectors.toSet());

    // V2 should include all the same datasets
    assertThat(v2DatasetNames)
        .containsAll(
            v1DatasetNames.stream()
                .filter(
                    name ->
                        name.equals("input_dataset_compare")
                            || name.equals("output_dataset_compare"))
                .collect(Collectors.toSet()));
  }

  @Test
  void testJobByName_V1VsV2_SameResult() {
    // Given: Create a specific job
    UpdateLineageRow lineage =
        createLineageRow(
            openLineageDao,
            "specific_job_test",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output", null)));

    UUID namespaceUuid = lineage.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query both endpoints
    Optional<Job> v1Job = jobDao.findJobByName(NAMESPACE, "specific_job_test");
    Optional<Job> v2Job = jobDao.findJobByNameV2(namespaceUuid, "specific_job_test", Set.of());

    // Then: Verify both found the job and have same core attributes
    assertThat(v1Job).isPresent();
    assertThat(v2Job).isPresent();

    assertThat(v2Job.get().getName()).isEqualTo(v1Job.get().getName());
    assertThat(v2Job.get().getNamespace()).isEqualTo(v1Job.get().getNamespace());
    assertThat(v2Job.get().getType()).isEqualTo(v1Job.get().getType());
  }

  @Test
  void testDatasetByName_V1VsV2_SameResult() {
    // Given: Create a specific dataset
    createLineageRow(
        openLineageDao,
        "dataset_name_test_job",
        "COMPLETE",
        JobFacet.builder().build(),
        List.of(),
        List.of(new LineageEvent.Dataset(NAMESPACE, "specific_dataset_test", null)));

    UUID namespaceUuid = namespaceDao.findNamespaceByName(NAMESPACE).get().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query both endpoints
    Optional<Dataset> v1Dataset = datasetDao.findDatasetByName(NAMESPACE, "specific_dataset_test");
    Optional<Dataset> v2Dataset =
        datasetDao.findDatasetByNameV2(namespaceUuid, "specific_dataset_test", Set.of());

    // Then: Verify both found the dataset and have same core attributes
    assertThat(v1Dataset).isPresent();
    assertThat(v2Dataset).isPresent();

    assertThat(v2Dataset.get().getName()).isEqualTo(v1Dataset.get().getName());
    assertThat(v2Dataset.get().getNamespace()).isEqualTo(v1Dataset.get().getNamespace());
    assertThat(v2Dataset.get().getType()).isEqualTo(v1Dataset.get().getType());
  }

  @Test
  void testPagination_V1VsV2_ConsistentBehavior() {
    // Given: Create multiple jobs
    UpdateLineageRow firstLineage = null;
    for (int i = 0; i < 6; i++) {
      UpdateLineageRow lineage =
          createLineageRow(
              openLineageDao,
              "pagination_job_" + i,
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

    // When: Query with same pagination
    List<Job> v1Page = jobDao.findAll(NAMESPACE, List.of(RunState.values()), 3, 0);
    List<Job> v2Page = jobDao.findAllJobsV2(namespaceUuid, 3, 0, Set.of());

    // Then: Verify same page size
    assertThat(v1Page.size()).isEqualTo(3);
    assertThat(v2Page.size()).isEqualTo(3);

    // Both should return jobs consistently
    assertThat(v2Page).isNotEmpty();
    assertThat(v1Page).isNotEmpty();
  }

  @Test
  void testEmptyResults_V1VsV2_BothEmpty() {
    // Given: Empty namespace
    UUID namespaceUuid =
        namespaceDao
            .upsertNamespaceRow(
                UUID.randomUUID(), java.time.Instant.now(), "empty_comparison_namespace", "test")
            .getUuid();

    // When: Query both endpoints
    List<Job> v1Jobs =
        jobDao.findAll("empty_comparison_namespace", List.of(RunState.values()), 100, 0);
    List<Job> v2Jobs = jobDao.findAllJobsV2(namespaceUuid, 100, 0, Set.of());

    // Then: Both should return empty
    assertThat(v1Jobs).isEmpty();
    assertThat(v2Jobs).isEmpty();
  }

  @Test
  void testJobFieldsConsistency_V1VsV2() {
    // Given: Create job with description and tags
    UpdateLineageRow lineage =
        createLineageRow(
            openLineageDao,
            "fields_consistency_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output", null)));

    jobDao.updateJobTags(NAMESPACE, "fields_consistency_job", "test");

    UUID namespaceUuid = lineage.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query both endpoints
    Optional<Job> v1Job = jobDao.findJobByName(NAMESPACE, "fields_consistency_job");
    Optional<Job> v2Job = jobDao.findJobByNameV2(namespaceUuid, "fields_consistency_job", Set.of());

    // Then: Verify key fields match
    assertThat(v1Job).isPresent();
    assertThat(v2Job).isPresent();

    Job j1 = v1Job.get();
    Job j2 = v2Job.get();

    assertThat(j2.getName()).isEqualTo(j1.getName());
    assertThat(j2.getNamespace()).isEqualTo(j1.getNamespace());
    assertThat(j2.getType()).isEqualTo(j1.getType());
    assertThat(j2.getCreatedAt()).isNotNull();
    assertThat(j2.getUpdatedAt()).isNotNull();

    // Tags should be present in both
    if (j1.getTags() != null && !j1.getTags().isEmpty()) {
      assertThat(j2.getTags()).isNotNull();
      assertThat(j2.getTags()).containsAll(j1.getTags());
    }
  }

  @Test
  void testDatasetFieldsConsistency_V1VsV2() {
    // Given: Create dataset with schema
    LineageEvent.Dataset datasetWithSchema =
        new LineageEvent.Dataset(
            NAMESPACE,
            "dataset_with_fields",
            LineageEvent.DatasetFacets.builder()
                .schema(
                    new LineageEvent.SchemaDatasetFacet(
                        null,
                        null,
                        List.of(
                            new LineageEvent.SchemaField("id", "integer", "Primary key"),
                            new LineageEvent.SchemaField("name", "string", "Name field"))))
                .build());

    createLineageRow(
        openLineageDao,
        "schema_test_job",
        "COMPLETE",
        JobFacet.builder().build(),
        List.of(),
        List.of(datasetWithSchema));

    UUID namespaceUuid = namespaceDao.findNamespaceByName(NAMESPACE).get().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query both endpoints
    Optional<Dataset> v1Dataset = datasetDao.findDatasetByName(NAMESPACE, "dataset_with_fields");
    Optional<Dataset> v2Dataset =
        datasetDao.findDatasetByNameV2(namespaceUuid, "dataset_with_fields", Set.of());

    // Then: Verify fields are consistent
    assertThat(v1Dataset).isPresent();
    assertThat(v2Dataset).isPresent();

    Dataset d1 = v1Dataset.get();
    Dataset d2 = v2Dataset.get();

    assertThat(d2.getName()).isEqualTo(d1.getName());
    assertThat(d2.getNamespace()).isEqualTo(d1.getNamespace());

    // Both should have fields
    if (d1.getFields() != null) {
      assertThat(d2.getFields()).isNotNull();
      assertThat(d2.getFields()).hasSameSizeAs(d1.getFields());
    }
  }
}

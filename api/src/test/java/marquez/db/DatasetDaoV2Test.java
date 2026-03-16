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
import marquez.service.models.Dataset;
import marquez.service.models.LineageEvent;
import marquez.service.models.LineageEvent.JobFacet;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for DatasetDao V2 methods that use denormalized tables. These tests verify
 * actual SQL execution against PostgreSQL.
 */
@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
class DatasetDaoV2Test {

  private static DatasetDao datasetDao;
  private static OpenLineageDao openLineageDao;
  private static NamespaceDao namespaceDao;
  private static DenormalizedLineageService denormalizedLineageService;
  private static Jdbi jdbi;

  @BeforeAll
  public static void setUpOnce(Jdbi jdbi) {
    DatasetDaoV2Test.jdbi = jdbi;
    datasetDao = jdbi.onDemand(DatasetDao.class);
    openLineageDao = jdbi.onDemand(OpenLineageDao.class);
    namespaceDao = jdbi.onDemand(NamespaceDao.class);

    PartitionManagementService partitionManagementService =
        new PartitionManagementService(jdbi, 10, 12);
    denormalizedLineageService = new DenormalizedLineageService(jdbi, partitionManagementService);
  }

  @AfterEach
  public void tearDown(Jdbi jdbi) {
    JdbiUtils.cleanDatabase(jdbi);
  }

  @Test
  void testFindAllDatasetsV2_WithSeededData() {
    // Given: Create lineage events with datasets
    UpdateLineageRow lineage1 =
        createLineageRow(
            openLineageDao,
            "test_job_1",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output_dataset_1", null)));

    UpdateLineageRow lineage2 =
        createLineageRow(
            openLineageDao,
            "test_job_2",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "input_dataset_1", null)),
            List.of(new LineageEvent.Dataset(NAMESPACE, "output_dataset_2", null)));

    // Populate denormalized tables
    UUID namespaceUuid = lineage1.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query V2 endpoint
    List<Dataset> datasets = datasetDao.findAllDatasetsV2(namespaceUuid, 100, 0, Set.of());

    // Then: Verify results
    assertThat(datasets).isNotNull();
    assertThat(datasets).hasSizeGreaterThanOrEqualTo(3);

    // Verify dataset names are present
    List<String> datasetNames = datasets.stream().map(d -> d.getName().getValue()).toList();
    assertThat(datasetNames).contains("output_dataset_1", "output_dataset_2", "input_dataset_1");

    // Verify all datasets have required fields
    datasets.forEach(
        dataset -> {
          assertThat(dataset.getId()).isNotNull();
          assertThat(dataset.getName()).isNotNull();
          assertThat(dataset.getNamespace()).isNotNull();
          assertThat(dataset.getCreatedAt()).isNotNull();
        });
  }

  @Test
  void testFindAllDatasetsV2_WithPagination() {
    // Given: Create multiple datasets
    for (int i = 0; i < 5; i++) {
      createLineageRow(
          openLineageDao,
          "test_job_" + i,
          "COMPLETE",
          JobFacet.builder().build(),
          List.of(),
          List.of(new LineageEvent.Dataset(NAMESPACE, "dataset_" + i, null)));
    }

    UUID namespaceUuid = namespaceDao.findNamespaceByName(NAMESPACE).get().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query with limit and offset
    List<Dataset> page1 = datasetDao.findAllDatasetsV2(namespaceUuid, 2, 0, Set.of());
    List<Dataset> page2 = datasetDao.findAllDatasetsV2(namespaceUuid, 2, 2, Set.of());

    // Then: Verify pagination works
    assertThat(page1).hasSize(2);
    assertThat(page2).hasSize(2);

    // Pages should not overlap
    Set<String> page1Ids =
        page1.stream()
            .map(d -> d.getId().getNamespace().getValue() + "." + d.getId().getName().getValue())
            .collect(java.util.stream.Collectors.toSet());
    Set<String> page2Ids =
        page2.stream()
            .map(d -> d.getId().getNamespace().getValue() + "." + d.getId().getName().getValue())
            .collect(java.util.stream.Collectors.toSet());
    assertThat(page1Ids).doesNotContainAnyElementsOf(page2Ids);
  }

  @Test
  void testFindDatasetByNameV2_ExistingDataset() {
    // Given: Create a dataset
    UpdateLineageRow lineage =
        createLineageRow(
            openLineageDao,
            "test_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "my_dataset", null)));

    UUID namespaceUuid = lineage.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query by name
    Optional<Dataset> dataset =
        datasetDao.findDatasetByNameV2(namespaceUuid, "my_dataset", Set.of());

    // Then: Verify dataset found
    assertThat(dataset).isPresent();
    assertThat(dataset.get().getName().getValue()).isEqualTo("my_dataset");
    assertThat(dataset.get().getNamespace().getValue()).isEqualTo(NAMESPACE);
  }

  @Test
  void testFindDatasetByNameV2_NonExistentDataset() {
    // Given: Create namespace
    UUID namespaceUuid =
        namespaceDao
            .findNamespaceByName(NAMESPACE)
            .orElseGet(
                () ->
                    namespaceDao.upsertNamespaceRow(
                        UUID.randomUUID(), java.time.Instant.now(), NAMESPACE, "test"))
            .getUuid();

    // When: Query non-existent dataset
    Optional<Dataset> dataset =
        datasetDao.findDatasetByNameV2(namespaceUuid, "non_existent_dataset", Set.of());

    // Then: Verify not found
    assertThat(dataset).isEmpty();
  }

  @Test
  void testFindAllDatasetsV2_EmptyNamespace() {
    // Given: Empty namespace
    UUID namespaceUuid =
        namespaceDao
            .upsertNamespaceRow(
                UUID.randomUUID(), java.time.Instant.now(), "empty_namespace", "test")
            .getUuid();

    // When: Query V2 endpoint
    List<Dataset> datasets = datasetDao.findAllDatasetsV2(namespaceUuid, 100, 0, Set.of());

    // Then: Verify empty result
    assertThat(datasets).isEmpty();
  }

  @Test
  void testFindAllDatasetsV2_WithFields() {
    // Given: Create dataset with schema
    LineageEvent.Dataset datasetWithSchema =
        new LineageEvent.Dataset(
            NAMESPACE,
            "dataset_with_schema",
            LineageEvent.DatasetFacets.builder()
                .schema(
                    new LineageEvent.SchemaDatasetFacet(
                        null,
                        null,
                        List.of(
                            new LineageEvent.SchemaField("id", "integer", "Primary key"),
                            new LineageEvent.SchemaField("name", "string", "User name"))))
                .build());

    UpdateLineageRow lineage =
        createLineageRow(
            openLineageDao,
            "test_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(datasetWithSchema));

    UUID namespaceUuid = lineage.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query V2 endpoint
    List<Dataset> datasets = datasetDao.findAllDatasetsV2(namespaceUuid, 100, 0, Set.of());

    // Then: Verify fields are included
    Optional<Dataset> dataset =
        datasets.stream()
            .filter(d -> d.getName().getValue().equals("dataset_with_schema"))
            .findFirst();

    assertThat(dataset).isPresent();
    assertThat(dataset.get().getFields()).isNotEmpty();
    assertThat(dataset.get().getFields()).hasSize(2);
  }

  @Test
  void testV2Methods_UseDenormalizedTables() {
    // Given: Create dataset
    UpdateLineageRow lineage =
        createLineageRow(
            openLineageDao,
            "test_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new LineageEvent.Dataset(NAMESPACE, "test_dataset", null)));

    UUID namespaceUuid = lineage.getNamespace().getUuid();
    denormalizedLineageService.populateDenormalizedEntitiesForNamespace(namespaceUuid);

    // When: Query V2 - should use denormalized tables (fast query)
    long startTime = System.currentTimeMillis();
    List<Dataset> datasets = datasetDao.findAllDatasetsV2(namespaceUuid, 100, 0, Set.of());
    long duration = System.currentTimeMillis() - startTime;

    // Then: Verify query completes and is reasonably fast
    assertThat(datasets).isNotEmpty();
    assertThat(duration).isLessThan(1000); // Should complete in under 1 second

    // Verify denormalized table was populated
    Long count =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        "SELECT COUNT(*) FROM dataset_denormalized WHERE namespace_uuid = :uuid")
                    .bind("uuid", namespaceUuid)
                    .mapTo(Long.class)
                    .one());
    assertThat(count).isGreaterThan(0);
  }
}

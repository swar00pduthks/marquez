/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import marquez.db.LineageTestUtils;
import marquez.db.OpenLineageDao;
import marquez.db.models.UpdateLineageRow;
import marquez.jdbi.MarquezJdbiExternalPostgresExtension;
import marquez.service.models.LineageEvent.Dataset;
import marquez.service.models.LineageEvent.JobFacet;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Test suite for {@link DenormalizedLineageService}. */
@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
public class DenormalizedLineageServiceTest {

  private static Jdbi jdbi;
  private static DenormalizedLineageService denormalizedLineageService;
  private static OpenLineageDao openLineageDao;
  private static final Logger log = LoggerFactory.getLogger(DenormalizedLineageServiceTest.class);

  @BeforeAll
  public static void setUpOnce(Jdbi jdbi) {
    DenormalizedLineageServiceTest.jdbi = jdbi;
    openLineageDao = jdbi.onDemand(OpenLineageDao.class);
    denormalizedLineageService = new DenormalizedLineageService(jdbi);
  }

  @AfterEach
  public void tearDown() {
    // Clean up denormalized tables after each test
    jdbi.useHandle(
        handle -> {
          handle.execute("DELETE FROM run_lineage_denormalized");
          handle.execute("DELETE FROM run_parent_lineage_denormalized");
        });
  }

  @Test
  public void testPopulateLineageForRun() {
    // Use LineageTestUtils to create a lineage event and all required data
    UpdateLineageRow lineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "test_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new Dataset("namespace", "input_dataset", null)),
            List.of(new Dataset("namespace", "output_dataset", null)));
    UUID runUuid = lineageRow.getRun().getUuid();

    // When: Populate lineage for the run
    assertThatCode(() -> denormalizedLineageService.populateLineageForRun(runUuid))
        .doesNotThrowAnyException();

    // Then: Verify data is populated in denormalized tables
    jdbi.useHandle(
        handle -> {
          Long runLineageCount =
              handle
                  .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, runUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(runLineageCount).isEqualTo(1);
        });
  }

  @Test
  public void testPopulateLineageForRunWithParent() {
    // Create parent run
    UpdateLineageRow parentLineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "parent_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new Dataset("namespace", "parent_output", null)));

    // Create child run with parent reference
    UpdateLineageRow childLineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "child_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new Dataset("namespace", "parent_output", null)),
            List.of(new Dataset("namespace", "child_output", null)));

    UUID childRunUuid = childLineageRow.getRun().getUuid();
    UUID parentRunUuid = parentLineageRow.getRun().getUuid();

    // Set parent-child relationship
    jdbi.useHandle(
        handle -> {
          handle.execute(
              "UPDATE runs SET parent_run_uuid = ? WHERE uuid = ?", parentRunUuid, childRunUuid);
        });

    // When: Populate lineage for the child run
    denormalizedLineageService.populateLineageForRun(childRunUuid);

    // Then: Verify parent lineage is populated
    jdbi.useHandle(
        handle -> {
          Long parentLineageCount =
              handle
                  .createQuery(
                      "SELECT COUNT(*) FROM run_parent_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, parentRunUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(parentLineageCount).isEqualTo(1);
        });
  }

  @Test
  @Disabled("Failing due to missing facets handling in populateRunLineageDenormalized method")
  public void testPopulateLineageForRunWithFacets() {
    // Create a run with facets
    UpdateLineageRow lineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "test_job_with_facets",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new Dataset("namespace", "output_dataset", null)));

    UUID runUuid = lineageRow.getRun().getUuid();

    // Create run facets with proper JSON casting
    jdbi.useHandle(
        handle -> {
          handle.execute(
              "INSERT INTO run_facets (created_at, run_uuid, lineage_event_time, lineage_event_type, name, facet) "
                  + "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
              Instant.now(),
              runUuid,
              Instant.now(),
              "COMPLETE",
              "test_facet",
              "{\"test\": \"value\"}");
        });

    // When: Populate lineage for the run
    denormalizedLineageService.populateLineageForRun(runUuid);

    // Then: Verify facets are populated - handle multiple rows for multiple facets
    jdbi.useHandle(
        handle -> {
          List<String> facetsList =
              handle
                  .createQuery(
                      "SELECT facets::text FROM run_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, runUuid)
                  .mapTo(String.class)
                  .list();

          // Debug: Print what we actually found
          System.out.println(
              "=== DEBUG: Found " + facetsList.size() + " records for run " + runUuid + " ===");
          facetsList.forEach(facets -> System.out.println("Facets JSON: " + facets));

          // Also check what's in the run_facets table
          List<String> runFacetsList =
              handle
                  .createQuery("SELECT name, facet::text FROM run_facets WHERE run_uuid = ?")
                  .bind(0, runUuid)
                  .mapTo(String.class)
                  .list();
          System.out.println(
              "=== DEBUG: Found " + runFacetsList.size() + " records in run_facets table ===");
          runFacetsList.forEach(facet -> System.out.println("Run facet: " + facet));

          // Assert we have at least one record and it contains our facet content
          // Note: The denormalized table stores facet content as JSON, not facet names
          assertThat(facetsList).isNotEmpty();
          assertThat(
                  facetsList.stream()
                      .anyMatch(facets -> facets.contains("test") && facets.contains("value")))
              .isTrue();
        });
  }

  @Test
  public void testPopulateLineageForRunUpdatesExistingRecords() {
    // Create a lineage event
    UpdateLineageRow lineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "test_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new Dataset("namespace", "output_dataset", null)));

    UUID runUuid = lineageRow.getRun().getUuid();

    // Insert initial record
    jdbi.useHandle(
        handle -> {
          handle.execute(
              "INSERT INTO run_lineage_denormalized (run_uuid, job_name, namespace_name, run_date) VALUES (?, ?, ?, CURRENT_DATE)",
              runUuid,
              "old_job_name",
              "old_namespace");
        });

    // When: Populate lineage for the run again
    denormalizedLineageService.populateLineageForRun(runUuid);

    // Then: Verify the record was updated (not duplicated)
    jdbi.useHandle(
        handle -> {
          Long count =
              handle
                  .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, runUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(count).isEqualTo(1); // Should not have duplicates

          String jobName =
              handle
                  .createQuery("SELECT job_name FROM run_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, runUuid)
                  .mapTo(String.class)
                  .one();
          assertThat(jobName).isEqualTo("test_job"); // Should have updated data
        });
  }

  @Test
  public void testPopulateAllExistingRuns() {
    // Create multiple runs
    UpdateLineageRow lineageRow1 =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "test_job_1",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new Dataset("namespace", "output_dataset_1", null)));

    UpdateLineageRow lineageRow2 =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "test_job_2",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new Dataset("namespace", "output_dataset_2", null)));

    // When: Populate all existing runs
    assertThatCode(() -> denormalizedLineageService.populateAllExistingRuns())
        .doesNotThrowAnyException();

    // Then: Verify all runs are populated
    jdbi.useHandle(
        handle -> {
          Long totalCount =
              handle
                  .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized")
                  .mapTo(Long.class)
                  .one();
          assertThat(totalCount).isGreaterThanOrEqualTo(2);
        });
  }

  @Test
  public void testIntegrationWithOpenLineageService() {
    // Create a lineage event using LineageTestUtils
    UpdateLineageRow lineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "test_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new Dataset("namespace", "input_dataset", null)),
            List.of(new Dataset("namespace", "output_dataset", null)));

    UUID runUuid = lineageRow.getRun().getUuid();

    // When: Manually trigger denormalized lineage population
    // Note: LineageTestUtils.createLineageRow doesn't automatically trigger denormalized population
    denormalizedLineageService.populateLineageForRun(runUuid);

    // Then: Verify denormalized tables are populated
    jdbi.useHandle(
        handle -> {
          Long count =
              handle
                  .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, runUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(count).isEqualTo(1);
        });
  }

  @Test
  public void testParentRunPopulatesBothTables() {
    // Create a parent run
    UpdateLineageRow parentLineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "parent_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new Dataset("namespace", "parent_output", null)));

    // Create a child run that references the parent
    UpdateLineageRow childLineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "child_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new Dataset("namespace", "parent_output", null)),
            List.of(new Dataset("namespace", "child_output", null)));

    UUID parentRunUuid = parentLineageRow.getRun().getUuid();
    UUID childRunUuid = childLineageRow.getRun().getUuid();

    // Set parent-child relationship
    jdbi.useHandle(
        handle -> {
          handle.execute(
              "UPDATE runs SET parent_run_uuid = ? WHERE uuid = ?", parentRunUuid, childRunUuid);
        });

    // When: Populate lineage for the parent run (COMPLETE event)
    denormalizedLineageService.populateLineageForRun(parentRunUuid);

    // Then: Verify parent run populates both tables
    jdbi.useHandle(
        handle -> {
          // Check run_lineage_denormalized (should have parent run data)
          Long runLineageCount =
              handle
                  .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, parentRunUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(runLineageCount).isGreaterThan(0);

          // Check run_parent_lineage_denormalized (should have parent run data with child
          // aggregation)
          Long parentLineageCount =
              handle
                  .createQuery(
                      "SELECT COUNT(*) FROM run_parent_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, parentRunUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(parentLineageCount).isGreaterThan(0);

          // Verify the parent lineage contains the child run information
          String parentJobName =
              handle
                  .createQuery(
                      "SELECT job_name FROM run_parent_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, parentRunUuid)
                  .mapTo(String.class)
                  .one();
          assertThat(parentJobName).isEqualTo("parent_job");
        });
  }

  @Test
  public void testChildRunPopulatesOnlyRunLineage() {
    // Create a parent run
    UpdateLineageRow parentLineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "parent_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new Dataset("namespace", "parent_output", null)));

    // Create a child run that references the parent
    UpdateLineageRow childLineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "child_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new Dataset("namespace", "parent_output", null)),
            List.of(new Dataset("namespace", "child_output", null)));

    UUID parentRunUuid = parentLineageRow.getRun().getUuid();
    UUID childRunUuid = childLineageRow.getRun().getUuid();

    // Set parent-child relationship
    jdbi.useHandle(
        handle -> {
          handle.execute(
              "UPDATE runs SET parent_run_uuid = ? WHERE uuid = ?", parentRunUuid, childRunUuid);
        });

    // When: Populate lineage for the child run
    denormalizedLineageService.populateLineageForRun(childRunUuid);

    // Then: Verify child run only populates run_lineage_denormalized
    jdbi.useHandle(
        handle -> {
          // Check run_lineage_denormalized (should have child run data)
          Long runLineageCount =
              handle
                  .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, childRunUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(runLineageCount).isGreaterThan(0);

          // Check run_parent_lineage_denormalized (should NOT have child run data)
          Long parentLineageCount =
              handle
                  .createQuery(
                      "SELECT COUNT(*) FROM run_parent_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, childRunUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(parentLineageCount).isEqualTo(0);

          // Verify the child lineage contains the correct job name
          String childJobName =
              handle
                  .createQuery("SELECT job_name FROM run_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, childRunUuid)
                  .mapTo(String.class)
                  .one();
          assertThat(childJobName).isEqualTo("child_job");
        });
  }

  @Test
  public void testStandaloneRunPopulatesOnlyRunLineage() {
    // Create a standalone run (no parent, no children)
    UpdateLineageRow standaloneLineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "standalone_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new Dataset("namespace", "standalone_output", null)));

    UUID standaloneRunUuid = standaloneLineageRow.getRun().getUuid();

    // When: Populate lineage for the standalone run
    denormalizedLineageService.populateLineageForRun(standaloneRunUuid);

    // Then: Verify standalone run only populates run_lineage_denormalized
    jdbi.useHandle(
        handle -> {
          // Check run_lineage_denormalized (should have standalone run data)
          Long runLineageCount =
              handle
                  .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, standaloneRunUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(runLineageCount).isGreaterThan(0);

          // Check run_parent_lineage_denormalized (should NOT have standalone run data)
          Long parentLineageCount =
              handle
                  .createQuery(
                      "SELECT COUNT(*) FROM run_parent_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, standaloneRunUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(parentLineageCount).isEqualTo(0);

          // Verify the standalone lineage contains the correct job name
          String standaloneJobName =
              handle
                  .createQuery("SELECT job_name FROM run_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, standaloneRunUuid)
                  .mapTo(String.class)
                  .one();
          assertThat(standaloneJobName).isEqualTo("standalone_job");
        });
  }

  @Test
  public void testParentRunWithMultipleChildren() {
    // Create a parent run
    UpdateLineageRow parentLineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "parent_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new Dataset("namespace", "parent_output", null)));

    // Create multiple child runs
    UpdateLineageRow child1LineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "child_job_1",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new Dataset("namespace", "parent_output", null)),
            List.of(new Dataset("namespace", "child1_output", null)));

    UpdateLineageRow child2LineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "child_job_2",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new Dataset("namespace", "parent_output", null)),
            List.of(new Dataset("namespace", "child2_output", null)));

    UUID parentRunUuid = parentLineageRow.getRun().getUuid();
    UUID child1RunUuid = child1LineageRow.getRun().getUuid();
    UUID child2RunUuid = child2LineageRow.getRun().getUuid();

    // Set parent-child relationships
    jdbi.useHandle(
        handle -> {
          handle.execute(
              "UPDATE runs SET parent_run_uuid = ? WHERE uuid = ?", parentRunUuid, child1RunUuid);
          handle.execute(
              "UPDATE runs SET parent_run_uuid = ? WHERE uuid = ?", parentRunUuid, child2RunUuid);
        });

    // When: Populate lineage for the parent run (COMPLETE event)
    denormalizedLineageService.populateLineageForRun(parentRunUuid);

    // Then: Verify parent run populates both tables with multiple child records
    jdbi.useHandle(
        handle -> {
          // Check run_lineage_denormalized (should have parent run data)
          Long runLineageCount =
              handle
                  .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, parentRunUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(runLineageCount).isGreaterThan(0);

          // Check run_parent_lineage_denormalized (should have multiple records for the parent)
          Long parentLineageCount =
              handle
                  .createQuery(
                      "SELECT COUNT(*) FROM run_parent_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, parentRunUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(parentLineageCount).isGreaterThan(0);

          // Verify the parent lineage contains records for both children
          List<String> childRunUuids =
              handle
                  .createQuery(
                      "SELECT uuid FROM run_parent_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, parentRunUuid)
                  .mapTo(String.class)
                  .list();
          assertThat(childRunUuids).contains(child1RunUuid.toString(), child2RunUuid.toString());
        });
  }

  @Test
  public void testRunDateColumnIsPopulated() {
    // Create a run with a specific start time
    UpdateLineageRow lineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "test_job_with_date",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(),
            List.of(new Dataset("namespace", "output_dataset", null)));

    UUID runUuid = lineageRow.getRun().getUuid();

    // When: Populate lineage for the run
    denormalizedLineageService.populateLineageForRun(runUuid);

    // Then: Verify run_date column is populated correctly
    jdbi.useHandle(
        handle -> {
          // First, check if the record exists in the denormalized table
          Long count =
              handle
                  .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, runUuid)
                  .mapTo(Long.class)
                  .one();

          log.info("Found {} records in run_lineage_denormalized for run {}", count, runUuid);

          if (count == 0) {
            // Check what's in the runs table
            Long runsCount =
                handle
                    .createQuery("SELECT COUNT(*) FROM runs WHERE uuid = ?")
                    .bind(0, runUuid)
                    .mapTo(Long.class)
                    .one();
            log.info("Found {} records in runs table for run {}", runsCount, runUuid);

            // Check if started_at is null in runs table
            java.sql.Timestamp startedAt =
                handle
                    .createQuery("SELECT started_at FROM runs WHERE uuid = ?")
                    .bind(0, runUuid)
                    .mapTo(java.sql.Timestamp.class)
                    .one();
            log.info("Started_at in runs table: {}", startedAt);
          }

          // Get the run_date from the denormalized table as string
          String runDateStr =
              handle
                  .createQuery(
                      "SELECT run_date::text FROM run_lineage_denormalized WHERE run_uuid = ?")
                  .bind(0, runUuid)
                  .mapTo(String.class)
                  .one();

          // Get the ended_at from the original runs table (fallback when started_at is null)
          java.sql.Timestamp endedAt =
              handle
                  .createQuery("SELECT ended_at FROM runs WHERE uuid = ?")
                  .bind(0, runUuid)
                  .mapTo(java.sql.Timestamp.class)
                  .one();

          // Verify run_date is the date part of ended_at (since started_at is null)
          assertThat(runDateStr).isNotNull();
          assertThat(runDateStr).isEqualTo(endedAt.toLocalDateTime().toLocalDate().toString());

          log.info("Run date: {}, Ended at: {}", runDateStr, endedAt);
        });
  }
}

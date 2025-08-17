/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db.migrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import marquez.api.JdbiUtils;
import marquez.db.LineageTestUtils;
import marquez.db.OpenLineageDao;
import marquez.db.models.UpdateLineageRow;
import marquez.jdbi.MarquezJdbiExternalPostgresExtension;
import marquez.service.models.LineageEvent.Dataset;
import marquez.service.models.LineageEvent.JobFacet;
import org.flywaydb.core.api.migration.Context;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@org.junit.jupiter.api.Tag("IntegrationTests")
@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
public class V78__BackfillDenormalizedTablesTest {

  private static V78__BackfillDenormalizedTables subject = new V78__BackfillDenormalizedTables();
  private static Jdbi jdbi;
  private static OpenLineageDao openLineageDao;

  Context flywayContext = mock(Context.class);
  Connection connection = mock(Connection.class);

  @BeforeAll
  public static void setUpOnce(Jdbi jdbi) {
    V78__BackfillDenormalizedTablesTest.jdbi = jdbi;
    openLineageDao = jdbi.onDemand(OpenLineageDao.class);
  }

  @AfterEach
  public void tearDown(Jdbi jdbi) {
    JdbiUtils.cleanDatabase(jdbi);
  }

  @BeforeEach
  public void beforeEach() {
    when(flywayContext.getConnection()).thenReturn(connection);
    subject.setChunkSize(10); // Small chunk size for testing
    subject.setManual(false);
    JdbiUtils.cleanDatabase(jdbi);

    // Ensure denormalized tables are also cleaned
    jdbi.useHandle(
        handle -> {
          handle.execute("DELETE FROM run_lineage_denormalized");
          handle.execute("DELETE FROM run_parent_lineage_denormalized");
        });
  }

  @Test
  public void testEmptyDatabase() throws Exception {
    try (MockedStatic<Jdbi> jdbiMockedStatic = Mockito.mockStatic(Jdbi.class)) {
      when(Jdbi.create(connection)).thenReturn(jdbi);

      // Ensure database is truly empty
      jdbi.useHandle(
          handle -> {
            handle.execute("DELETE FROM runs");
            handle.execute("DELETE FROM run_lineage_denormalized");
            handle.execute("DELETE FROM run_parent_lineage_denormalized");
          });

      assertThatCode(() -> subject.migrate(flywayContext)).doesNotThrowAnyException();

      // Verify no data was inserted
      jdbi.useHandle(
          handle -> {
            Long runLineageCount =
                handle
                    .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized")
                    .mapTo(Long.class)
                    .one();
            assertThat(runLineageCount).isEqualTo(0);

            Long parentLineageCount =
                handle
                    .createQuery("SELECT COUNT(*) FROM run_parent_lineage_denormalized")
                    .mapTo(Long.class)
                    .one();
            assertThat(parentLineageCount).isEqualTo(0);
          });
    }
  }

  @Test
  public void testSmallDatasetMigration() throws Exception {
    // Create test runs with proper input/output datasets
    UpdateLineageRow lineageRow1 =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "test_job_1",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new Dataset("namespace", "input_dataset_1", null)),
            List.of(new Dataset("namespace", "output_dataset_1", null)));

    UpdateLineageRow lineageRow2 =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "test_job_2",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new Dataset("namespace", "input_dataset_2", null)),
            List.of(new Dataset("namespace", "output_dataset_2", null)));

    // Verify that the test data was created properly
    jdbi.useHandle(
        handle -> {
          Long runsCount = handle.createQuery("SELECT COUNT(*) FROM runs").mapTo(Long.class).one();
          assertThat(runsCount).isEqualTo(2);

          Long datasetVersionsCount =
              handle.createQuery("SELECT COUNT(*) FROM dataset_versions").mapTo(Long.class).one();
          assertThat(datasetVersionsCount).isGreaterThan(0);

          Long runsInputMappingCount =
              handle.createQuery("SELECT COUNT(*) FROM runs_input_mapping").mapTo(Long.class).one();
          assertThat(runsInputMappingCount).isGreaterThan(0);
        });

    try (MockedStatic<Jdbi> jdbiMockedStatic = Mockito.mockStatic(Jdbi.class)) {
      when(Jdbi.create(connection)).thenReturn(jdbi);

      // Clear existing denormalized data
      jdbi.useHandle(
          handle -> {
            handle.execute("DELETE FROM run_lineage_denormalized");
            handle.execute("DELETE FROM run_parent_lineage_denormalized");
          });

      assertThatCode(() -> subject.migrate(flywayContext)).doesNotThrowAnyException();

      // Verify data was migrated
      jdbi.useHandle(
          handle -> {
            Long runLineageCount =
                handle
                    .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized")
                    .mapTo(Long.class)
                    .one();
            assertThat(runLineageCount).isEqualTo(2);

            // Verify specific run data
            String jobName1 =
                handle
                    .createQuery("SELECT job_name FROM run_lineage_denormalized WHERE run_uuid = ?")
                    .bind(0, lineageRow1.getRun().getUuid())
                    .mapTo(String.class)
                    .one();
            assertThat(jobName1).isEqualTo("test_job_1");

            String jobName2 =
                handle
                    .createQuery("SELECT job_name FROM run_lineage_denormalized WHERE run_uuid = ?")
                    .bind(0, lineageRow2.getRun().getUuid())
                    .mapTo(String.class)
                    .one();
            assertThat(jobName2).isEqualTo("test_job_2");
          });
    }
  }

  @Test
  public void testLargeDatasetManualMigration() throws Exception {
    // Create more runs than the chunk size
    for (int i = 0; i < 25; i++) {
      LineageTestUtils.createLineageRow(
          openLineageDao,
          "test_job_" + i,
          "COMPLETE",
          JobFacet.builder().build(),
          List.of(),
          List.of(new Dataset("namespace", "output_dataset_" + i, null)));
    }

    try (MockedStatic<Jdbi> jdbiMockedStatic = Mockito.mockStatic(Jdbi.class)) {
      when(Jdbi.create(connection)).thenReturn(jdbi);

      // Set manual mode to bypass the size limit
      subject.setManual(true);

      // Clear existing denormalized data
      jdbi.useHandle(
          handle -> {
            handle.execute("DELETE FROM run_lineage_denormalized");
            handle.execute("DELETE FROM run_parent_lineage_denormalized");
          });

      assertThatCode(() -> subject.migrate(flywayContext)).doesNotThrowAnyException();

      // Verify all data was migrated
      jdbi.useHandle(
          handle -> {
            Long runLineageCount =
                handle
                    .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized")
                    .mapTo(Long.class)
                    .one();
            assertThat(runLineageCount).isEqualTo(25);
          });
    }
  }

  @Test
  public void testMultipleRunsMigration() throws Exception {
    // Create a few test runs with proper input/output datasets
    for (int i = 0; i < 5; i++) {
      LineageTestUtils.createLineageRow(
          openLineageDao,
          "test_job_" + i,
          "COMPLETE",
          JobFacet.builder().build(),
          List.of(new Dataset("namespace", "input_dataset_" + i, null)),
          List.of(new Dataset("namespace", "output_dataset_" + i, null)));
    }

    // Verify that the test data was created properly
    jdbi.useHandle(
        handle -> {
          Long runsCount = handle.createQuery("SELECT COUNT(*) FROM runs").mapTo(Long.class).one();
          assertThat(runsCount).isEqualTo(5);

          Long datasetVersionsCount =
              handle.createQuery("SELECT COUNT(*) FROM dataset_versions").mapTo(Long.class).one();
          assertThat(datasetVersionsCount).isGreaterThan(0);

          Long runsInputMappingCount =
              handle.createQuery("SELECT COUNT(*) FROM runs_input_mapping").mapTo(Long.class).one();
          assertThat(runsInputMappingCount).isGreaterThan(0);
        });

    try (MockedStatic<Jdbi> jdbiMockedStatic = Mockito.mockStatic(Jdbi.class)) {
      when(Jdbi.create(connection)).thenReturn(jdbi);

      // Clear existing denormalized data
      jdbi.useHandle(
          handle -> {
            handle.execute("DELETE FROM run_lineage_denormalized");
            handle.execute("DELETE FROM run_parent_lineage_denormalized");
          });

      // For this test, we'll just verify that the migration runs without error
      // The actual skip logic would be tested in integration tests with real large datasets
      assertThatCode(() -> subject.migrate(flywayContext)).doesNotThrowAnyException();

      // Verify data was migrated (since we have a small dataset)
      jdbi.useHandle(
          handle -> {
            Long runLineageCount =
                handle
                    .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized")
                    .mapTo(Long.class)
                    .one();
            assertThat(runLineageCount).isEqualTo(5);
          });
    }
  }

  @Test
  public void testParentChildRunMigration() throws Exception {
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

    // Manually set parent relationship
    jdbi.useHandle(
        handle -> {
          handle
              .createUpdate("UPDATE runs SET parent_run_uuid = ? WHERE uuid = ?")
              .bind(0, parentLineageRow.getRun().getUuid())
              .bind(1, childLineageRow.getRun().getUuid())
              .execute();
        });

    try (MockedStatic<Jdbi> jdbiMockedStatic = Mockito.mockStatic(Jdbi.class)) {
      when(Jdbi.create(connection)).thenReturn(jdbi);

      // Clear existing denormalized data
      jdbi.useHandle(
          handle -> {
            handle.execute("DELETE FROM run_lineage_denormalized");
            handle.execute("DELETE FROM run_parent_lineage_denormalized");
          });

      assertThatCode(() -> subject.migrate(flywayContext)).doesNotThrowAnyException();

      // Verify both tables are populated
      jdbi.useHandle(
          handle -> {
            Long runLineageCount =
                handle
                    .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized")
                    .mapTo(Long.class)
                    .one();
            assertThat(runLineageCount).isEqualTo(2);

            Long parentLineageCount =
                handle
                    .createQuery("SELECT COUNT(*) FROM run_parent_lineage_denormalized")
                    .mapTo(Long.class)
                    .one();
            assertThat(parentLineageCount).isGreaterThan(0);
          });
    }
  }

  @Test
  public void testChunkedProcessing() throws Exception {
    // Create exactly chunk size + 1 runs (using test chunk size of 10)
    for (int i = 0; i < 11; i++) {
      LineageTestUtils.createLineageRow(
          openLineageDao,
          "test_job_" + i,
          "COMPLETE",
          JobFacet.builder().build(),
          List.of(),
          List.of(new Dataset("namespace", "output_dataset_" + i, null)));
    }

    try (MockedStatic<Jdbi> jdbiMockedStatic = Mockito.mockStatic(Jdbi.class)) {
      when(Jdbi.create(connection)).thenReturn(jdbi);

      // Clear existing denormalized data
      jdbi.useHandle(
          handle -> {
            handle.execute("DELETE FROM run_lineage_denormalized");
            handle.execute("DELETE FROM run_parent_lineage_denormalized");
          });

      assertThatCode(() -> subject.migrate(flywayContext)).doesNotThrowAnyException();

      // Verify all data was migrated across chunks
      jdbi.useHandle(
          handle -> {
            Long runLineageCount =
                handle
                    .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized")
                    .mapTo(Long.class)
                    .one();
            assertThat(runLineageCount).isEqualTo(11);
          });
    }
  }

  @Test
  public void testErrorHandling() throws Exception {
    // Create a valid run with proper input/output datasets
    UpdateLineageRow validLineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "valid_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new Dataset("namespace", "input_dataset", null)),
            List.of(new Dataset("namespace", "output_dataset", null)));

    // Create an invalid run by corrupting the data
    UpdateLineageRow invalidLineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "invalid_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new Dataset("namespace", "input_dataset_invalid", null)),
            List.of(new Dataset("namespace", "output_dataset_invalid", null)));

    // Corrupt the invalid run's data
    jdbi.useHandle(
        handle -> {
          handle
              .createUpdate("UPDATE runs SET namespace_name = NULL WHERE uuid = ?")
              .bind(0, invalidLineageRow.getRun().getUuid())
              .execute();
        });

    try (MockedStatic<Jdbi> jdbiMockedStatic = Mockito.mockStatic(Jdbi.class)) {
      when(Jdbi.create(connection)).thenReturn(jdbi);

      // Clear existing denormalized data
      jdbi.useHandle(
          handle -> {
            handle.execute("DELETE FROM run_lineage_denormalized");
            handle.execute("DELETE FROM run_parent_lineage_denormalized");
          });

      // Migration should not throw exception, should handle errors gracefully
      assertThatCode(() -> subject.migrate(flywayContext)).doesNotThrowAnyException();

      // Verify at least the valid run was migrated
      jdbi.useHandle(
          handle -> {
            Long runLineageCount =
                handle
                    .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized")
                    .mapTo(Long.class)
                    .one();
            assertThat(runLineageCount).isGreaterThanOrEqualTo(1);

            // Verify the valid run exists
            String jobName =
                handle
                    .createQuery("SELECT job_name FROM run_lineage_denormalized WHERE run_uuid = ?")
                    .bind(0, validLineageRow.getRun().getUuid())
                    .mapTo(String.class)
                    .one();
            assertThat(jobName).isEqualTo("valid_job");
          });
    }
  }

  @Test
  public void testDataStructureVerification() throws Exception {
    // Create a comprehensive test run with all required data
    UpdateLineageRow lineageRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "comprehensive_job",
            "COMPLETE",
            JobFacet.builder().build(),
            List.of(new Dataset("namespace", "comprehensive_input", null)),
            List.of(new Dataset("namespace", "comprehensive_output", null)));

    UUID runUuid = lineageRow.getRun().getUuid();

    // Verify all required tables have data
    jdbi.useHandle(
        handle -> {
          // Check runs table
          Long runsCount =
              handle
                  .createQuery("SELECT COUNT(*) FROM runs WHERE uuid = ?")
                  .bind(0, runUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(runsCount).isEqualTo(1);

          // Check dataset_versions table
          Long datasetVersionsCount =
              handle.createQuery("SELECT COUNT(*) FROM dataset_versions").mapTo(Long.class).one();
          assertThat(datasetVersionsCount).isGreaterThan(0);

          // Check runs_input_mapping table
          Long runsInputMappingCount =
              handle
                  .createQuery("SELECT COUNT(*) FROM runs_input_mapping WHERE run_uuid = ?")
                  .bind(0, runUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(runsInputMappingCount).isGreaterThan(0);

          // Check that dataset_versions are linked to the run
          Long outputDatasetVersionsCount =
              handle
                  .createQuery("SELECT COUNT(*) FROM dataset_versions WHERE run_uuid = ?")
                  .bind(0, runUuid)
                  .mapTo(Long.class)
                  .one();
          assertThat(outputDatasetVersionsCount).isGreaterThan(0);
        });

    try (MockedStatic<Jdbi> jdbiMockedStatic = Mockito.mockStatic(Jdbi.class)) {
      when(Jdbi.create(connection)).thenReturn(jdbi);

      // Clear existing denormalized data
      jdbi.useHandle(
          handle -> {
            handle.execute("DELETE FROM run_lineage_denormalized");
            handle.execute("DELETE FROM run_parent_lineage_denormalized");
          });

      assertThatCode(() -> subject.migrate(flywayContext)).doesNotThrowAnyException();

      // Verify the migration populated the denormalized table correctly
      jdbi.useHandle(
          handle -> {
            Long runLineageCount =
                handle
                    .createQuery("SELECT COUNT(*) FROM run_lineage_denormalized WHERE run_uuid = ?")
                    .bind(0, runUuid)
                    .mapTo(Long.class)
                    .one();
            assertThat(runLineageCount).isEqualTo(1);

            // Verify the denormalized data has the expected structure
            String jobName =
                handle
                    .createQuery("SELECT job_name FROM run_lineage_denormalized WHERE run_uuid = ?")
                    .bind(0, runUuid)
                    .mapTo(String.class)
                    .one();
            assertThat(jobName).isEqualTo("comprehensive_job");

            // Verify input dataset information is populated
            String inputDatasetName =
                handle
                    .createQuery(
                        "SELECT input_dataset_name FROM run_lineage_denormalized WHERE run_uuid = ?")
                    .bind(0, runUuid)
                    .mapTo(String.class)
                    .one();
            assertThat(inputDatasetName).isEqualTo("comprehensive_input");

            // Verify output dataset information is populated
            String outputDatasetName =
                handle
                    .createQuery(
                        "SELECT output_dataset_name FROM run_lineage_denormalized WHERE run_uuid = ?")
                    .bind(0, runUuid)
                    .mapTo(String.class)
                    .one();
            assertThat(outputDatasetName).isEqualTo("comprehensive_output");
          });
    }
  }
}

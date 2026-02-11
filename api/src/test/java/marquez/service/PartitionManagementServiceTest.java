/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDate;
import marquez.jdbi.MarquezJdbiExternalPostgresExtension;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Test suite for {@link PartitionManagementService}. */
@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
public class PartitionManagementServiceTest {

  private static Jdbi jdbi;
  private static PartitionManagementService partitionManagementService;
  private static final Logger log = LoggerFactory.getLogger(PartitionManagementServiceTest.class);

  @BeforeAll
  public static void setUpOnce(Jdbi jdbi) {
    PartitionManagementServiceTest.jdbi = jdbi;
    partitionManagementService = new PartitionManagementService(jdbi, 10, 12);
  }

  @Test
  public void testEnsurePartitionExists() {
    // Given: A date for which we want to create a partition
    LocalDate testDate = LocalDate.of(2026, 2, 15);

    // When: We ensure partition exists for that date
    assertThatCode(() -> partitionManagementService.ensurePartitionExists(testDate))
        .doesNotThrowAnyException();

    // Then: Verify partitions were created for both tables
    jdbi.useHandle(
        handle -> {
          // Check run_lineage_denormalized partition
          String runLineagePartitionName = "run_lineage_denormalized_y2026m02";
          Long runLineagePartitionCount =
              handle
                  .createQuery(
                      "SELECT COUNT(*) FROM pg_tables WHERE tablename = :partitionName AND schemaname = current_schema()")
                  .bind("partitionName", runLineagePartitionName)
                  .mapTo(Long.class)
                  .one();

          assertThat(runLineagePartitionCount)
              .withFailMessage(
                  "Partition %s should exist for run_lineage_denormalized", runLineagePartitionName)
              .isEqualTo(1);

          // Check run_parent_lineage_denormalized partition
          String parentLineagePartitionName = "run_parent_lineage_denormalized_y2026m02";
          Long parentLineagePartitionCount =
              handle
                  .createQuery(
                      "SELECT COUNT(*) FROM pg_tables WHERE tablename = :partitionName AND schemaname = current_schema()")
                  .bind("partitionName", parentLineagePartitionName)
                  .mapTo(Long.class)
                  .one();

          assertThat(parentLineagePartitionCount)
              .withFailMessage(
                  "Partition %s should exist for run_parent_lineage_denormalized",
                  parentLineagePartitionName)
              .isEqualTo(1);

          log.info(
              "Successfully verified partitions: {}, {}",
              runLineagePartitionName,
              parentLineagePartitionName);
        });
  }

  @Test
  public void testEnsurePartitionExistsIsIdempotent() {
    // This test verifies the fix in V85__fix_partition_management_function.sql
    // The function should handle existing partitions gracefully without throwing errors

    // Given: A date for which we want to create a partition
    LocalDate testDate = LocalDate.of(2026, 3, 10);

    // When: We call ensurePartitionExists multiple times for the same date
    // Then: No exception should be thrown (idempotent behavior)
    assertThatCode(() -> partitionManagementService.ensurePartitionExists(testDate))
        .doesNotThrowAnyException();

    assertThatCode(() -> partitionManagementService.ensurePartitionExists(testDate))
        .doesNotThrowAnyException();

    assertThatCode(() -> partitionManagementService.ensurePartitionExists(testDate))
        .doesNotThrowAnyException();

    // Verify partition still exists and wasn't duplicated
    jdbi.useHandle(
        handle -> {
          String partitionName = "run_lineage_denormalized_y2026m03";
          Long partitionCount =
              handle
                  .createQuery(
                      "SELECT COUNT(*) FROM pg_tables WHERE tablename = :partitionName AND schemaname = current_schema()")
                  .bind("partitionName", partitionName)
                  .mapTo(Long.class)
                  .one();

          assertThat(partitionCount)
              .withFailMessage(
                  "Should have exactly 1 partition named %s, not multiple duplicates",
                  partitionName)
              .isEqualTo(1);

          log.info("Successfully verified idempotent partition creation for: {}", partitionName);
        });
  }

  @Test
  public void testPartitionIndexesAreCreated() {
    // Given: A date for which we want to create a partition
    LocalDate testDate = LocalDate.of(2026, 4, 1);

    // When: We ensure partition exists
    partitionManagementService.ensurePartitionExists(testDate);

    // Then: Verify indexes were created on the partition
    jdbi.useHandle(
        handle -> {
          String partitionName = "run_lineage_denormalized_y2026m04";

          // Count indexes on the partition
          Long indexCount =
              handle
                  .createQuery(
                      "SELECT COUNT(*) FROM pg_indexes "
                          + "WHERE tablename = :partitionName "
                          + "AND schemaname = current_schema()")
                  .bind("partitionName", partitionName)
                  .mapTo(Long.class)
                  .one();

          assertThat(indexCount)
              .withFailMessage(
                  "Partition %s should have at least 3 indexes (run_date, namespace_job, state_created)",
                  partitionName)
              .isGreaterThanOrEqualTo(3);

          // Verify specific index exists (run_date)
          Boolean runDateIndexExists =
              handle
                  .createQuery(
                      "SELECT EXISTS(SELECT 1 FROM pg_indexes "
                          + "WHERE tablename = :partitionName "
                          + "AND indexname LIKE '%run_date%' "
                          + "AND schemaname = current_schema())")
                  .bind("partitionName", partitionName)
                  .mapTo(Boolean.class)
                  .one();

          assertThat(runDateIndexExists)
              .withFailMessage("Index on run_date should exist for partition %s", partitionName)
              .isTrue();

          log.info("Successfully verified {} indexes on partition: {}", indexCount, partitionName);
        });
  }

  @Test
  public void testCreatePartitionsForPeriod() {
    // Given: A start date and number of months
    LocalDate startDate = LocalDate.of(2026, 5, 1);
    int months = 2; // Span across 2 months (May and June)

    // When: We create partitions for the period
    assertThatCode(() -> partitionManagementService.createPartitionsForPeriod(startDate, months))
        .doesNotThrowAnyException();

    // Then: Verify both May and June partitions were created
    jdbi.useHandle(
        handle -> {
          // Check May partition
          String mayPartition = "run_lineage_denormalized_y2026m05";
          Long mayCount =
              handle
                  .createQuery(
                      "SELECT COUNT(*) FROM pg_tables WHERE tablename = :partitionName AND schemaname = current_schema()")
                  .bind("partitionName", mayPartition)
                  .mapTo(Long.class)
                  .one();

          assertThat(mayCount).withFailMessage("May partition should exist").isEqualTo(1);

          // Check June partition
          String junePartition = "run_lineage_denormalized_y2026m06";
          Long juneCount =
              handle
                  .createQuery(
                      "SELECT COUNT(*) FROM pg_tables WHERE tablename = :partitionName AND schemaname = current_schema()")
                  .bind("partitionName", junePartition)
                  .mapTo(Long.class)
                  .one();

          assertThat(juneCount).withFailMessage("June partition should exist").isEqualTo(1);

          log.info(
              "Successfully verified partitions for period: {} and {}",
              mayPartition,
              junePartition);
        });
  }

  @Test
  public void testPartitionDateRanges() {
    // This test verifies that partitions are created with correct date ranges
    LocalDate testDate = LocalDate.of(2026, 7, 15);

    // When: Create partition for July 2026
    partitionManagementService.ensurePartitionExists(testDate);

    // Then: Verify the partition accepts dates in the range [2026-07-01, 2026-08-01)
    jdbi.useHandle(
        handle -> {
          String partitionName = "run_lineage_denormalized_y2026m07";

          // Insert a test record with a date in July 2026
          handle.execute(
              "INSERT INTO run_lineage_denormalized "
                  + "(run_uuid, namespace_name, job_name, state, created_at, updated_at, "
                  + "started_at, ended_at, job_uuid, job_version_uuid, uuid, run_date) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              java.util.UUID.randomUUID(),
              "test_namespace",
              "test_job",
              "COMPLETE",
              java.time.Instant.now(),
              java.time.Instant.now(),
              java.time.Instant.now(),
              java.time.Instant.now(),
              java.util.UUID.randomUUID(),
              java.util.UUID.randomUUID(),
              java.util.UUID.randomUUID(),
              LocalDate.of(2026, 7, 20) // Date in July
              );

          // Verify the record was inserted into the correct partition
          Long recordCount =
              handle
                  .createQuery(
                      "SELECT COUNT(*) FROM " + partitionName + " WHERE run_date = ?::date")
                  .bind(0, LocalDate.of(2026, 7, 20))
                  .mapTo(Long.class)
                  .one();

          assertThat(recordCount)
              .withFailMessage("Record should be in the July partition")
              .isEqualTo(1);

          // Clean up
          handle.execute(
              "DELETE FROM run_lineage_denormalized WHERE namespace_name = ?", "test_namespace");

          log.info("Successfully verified date range for partition: {}", partitionName);
        });
  }
}

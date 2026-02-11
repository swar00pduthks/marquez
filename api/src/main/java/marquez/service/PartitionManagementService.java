/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for managing database partitions for denormalized lineage tables. This service handles
 * creating new partitions and cleaning up old ones.
 */
public class PartitionManagementService {
  private static final Logger log = LoggerFactory.getLogger(PartitionManagementService.class);

  private final Jdbi jdbi;
  private final int monthsAhead;
  private final int retentionMonths;

  public PartitionManagementService(Jdbi jdbi, int monthsAhead, int retentionMonths) {
    this.jdbi = jdbi;
    this.monthsAhead = monthsAhead;
    this.retentionMonths = retentionMonths;
  }

  /** Ensures that a monthly partition exists for the given date. */
  public void ensurePartitionExists(LocalDate date) {
    log.debug("Ensuring partition exists for date: {}", date);

    // Normalize date to the first day of the month since partitions are monthly
    LocalDate firstOfMonth = date.withDayOfMonth(1);

    jdbi.useHandle(
        handle -> {
          // Create partition for run_lineage_denormalized
          handle.execute(
              "SELECT create_monthly_partition('run_lineage_denormalized', ?::date)", firstOfMonth);

          // Create partition for run_parent_lineage_denormalized
          handle.execute(
              "SELECT create_monthly_partition('run_parent_lineage_denormalized', ?::date)",
              firstOfMonth);
        });
  }

  /** Creates partitions for the next N months starting from the given date. */
  public void createPartitionsForPeriod(LocalDate startDate, int months) {
    log.info("Creating partitions for {} months starting from {}", months, startDate);

    for (int i = 0; i < months; i++) {
      LocalDate currentDate = startDate.plusMonths(i);
      ensurePartitionExists(currentDate);
    }
  }

  /** Creates partitions for the next N months starting from today. */
  public void createUpcomingPartitions() {
    createPartitionsForPeriod(LocalDate.now(), monthsAhead);
  }

  /** Drops old partitions based on retention policy. */
  public void cleanupOldPartitions() {
    log.info("Cleaning up partitions older than {} months", retentionMonths);

    jdbi.useHandle(
        handle -> {
          // Clean up run_lineage_denormalized partitions
          handle.execute(
              "SELECT drop_old_partitions('run_lineage_denormalized', ?)", retentionMonths);

          // Clean up run_parent_lineage_denormalized partitions
          handle.execute(
              "SELECT drop_old_partitions('run_parent_lineage_denormalized', ?)", retentionMonths);
        });
  }

  /** Gets statistics about existing partitions. */
  public Map<String, Object> getPartitionStats() {
    return jdbi.withHandle(
        handle -> {
          // Get partition statistics
          List<Map<String, Object>> partitions =
              handle
                  .createQuery(
                      """
              SELECT
                  schemaname,
                  tablename,
                  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as size,
                  pg_total_relation_size(schemaname||'.'||tablename) as size_bytes
              FROM pg_tables
              WHERE tablename LIKE 'run_lineage_denormalized_y%'
                 OR tablename LIKE 'run_parent_lineage_denormalized_y%'
              ORDER BY tablename
              """)
                  .mapToMap()
                  .list();

          return Map.of("partitions", partitions, "total_partitions", partitions.size());
        });
  }

  /** Analyzes all partitions to update statistics. */
  public void analyzePartitions() {
    log.info("Analyzing all partitions");

    jdbi.useHandle(
        handle -> {
          // Analyze run_lineage_denormalized partitions
          handle.execute("ANALYZE run_lineage_denormalized");

          // Analyze run_parent_lineage_denormalized partitions
          handle.execute("ANALYZE run_parent_lineage_denormalized");
        });
  }

  /** Performs maintenance tasks: creates upcoming partitions and cleans up old ones. */
  public void performMaintenance() {
    log.info("Performing partition maintenance");

    try {
      // Create upcoming partitions
      createUpcomingPartitions();

      // Clean up old partitions
      cleanupOldPartitions();

      // Analyze partitions for better query planning
      analyzePartitions();

      log.info("Partition maintenance completed successfully");
    } catch (Exception e) {
      log.error("Error during partition maintenance", e);
      throw new RuntimeException("Partition maintenance failed", e);
    }
  }
}

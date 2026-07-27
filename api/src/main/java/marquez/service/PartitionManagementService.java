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
  /**
   * Ensures that a partition exists for the given namespace. Currently a no-op for extensibility.
   *
   * @param namespaceUuid the UUID of the namespace
   */
  public void ensureNamespacePartitionExists(java.util.UUID namespaceUuid) {
    // No-op: Namespace partitioning not implemented yet, but method exists for future use.
    log.debug("Ensured (no-op) partition exists for namespace: {}", namespaceUuid);
  }

  private static final Logger log = LoggerFactory.getLogger(PartitionManagementService.class);

  /**
   * Composite RANGE(date)->HASH(namespace) tables (V107 lineage_edges, V108 run_facets). These are
   * managed by the V110 helpers, not the single-level create_monthly_partition/drop_old_partitions.
   *
   * <p>{@code partitionPrefix} differs from {@code parentTable} for run_facets: it was partitioned
   * via shadow-table swap, so its monthly partitions keep the {@code _p} shadow prefix
   * (run_facets_p_y2026m01) even though the parent is now {@code run_facets}. {@code
   * retentionMonths} follows the design doc: run_facets 12 months, lineage_edges 24 months.
   */
  private record CompositePartition(
      String parentTable,
      String partitionPrefix,
      String hashColumn,
      int hashModulus,
      int retentionMonths) {}

  private static final List<CompositePartition> COMPOSITE_PARTITIONS =
      List.of(
          new CompositePartition("lineage_edges", "lineage_edges", "namespace", 8, 24),
          new CompositePartition("run_facets", "run_facets_p", "namespace", 8, 12),
          new CompositePartition("dataset_facets", "dataset_facets_p", "namespace", 8, 12),
          new CompositePartition("lineage_events", "lineage_events_p", "job_namespace", 8, 24));

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

  /**
   * Creates the composite RANGE->HASH monthly subtree for the lineage_edges / run_facets tables for
   * the given date's month. Kept separate from {@link #ensurePartitionExists(LocalDate)} because
   * the latter is also invoked from historical Flyway migrations (e.g. V86) that run before
   * V107/V108 created these tables and before V110 created the helper functions — this method must
   * only be called at runtime (after all migrations), e.g. by {@code PartitionManagementJob}.
   */
  public void ensureCompositePartitionExists(LocalDate date) {
    LocalDate firstOfMonth = date.withDayOfMonth(1);
    jdbi.useHandle(
        handle -> {
          for (CompositePartition cp : COMPOSITE_PARTITIONS) {
            // A composite table may not be partitioned yet: on a large install run_facets stays a
            // plain table until RUN_FACETS_PARTITION_V1 performs the swap. Skip until then.
            if (!isPartitioned(handle, cp.parentTable())) {
              continue;
            }
            handle.execute(
                "SELECT create_monthly_hash_partition(?, ?, ?::date, ?, ?)",
                cp.parentTable(),
                cp.partitionPrefix(),
                firstOfMonth,
                cp.hashModulus(),
                cp.hashColumn());
          }
        });
  }

  private boolean isPartitioned(org.jdbi.v3.core.Handle handle, String table) {
    return handle
        .createQuery(
            "SELECT EXISTS(SELECT 1 FROM pg_partitioned_table WHERE partrelid = to_regclass(:t))")
        .bind("t", table)
        .mapTo(Boolean.class)
        .one();
  }

  /**
   * Creates composite RANGE->HASH partitions for the next N months starting from the given date.
   */
  public void createCompositePartitionsForPeriod(LocalDate startDate, int months) {
    for (int i = 0; i < months; i++) {
      ensureCompositePartitionExists(startDate.plusMonths(i));
    }
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

    cleanupOldCompositePartitions();
  }

  /**
   * Drops monthly partitions of the composite RANGE->HASH tables that fall outside each table's
   * retention window (DROP ... CASCADE removes the month's hash sub-partitions with it). The
   * DEFAULT partition and any month within retention are preserved.
   */
  public void cleanupOldCompositePartitions() {
    jdbi.useHandle(
        handle -> {
          for (CompositePartition cp : COMPOSITE_PARTITIONS) {
            if (!isPartitioned(handle, cp.parentTable())) {
              continue;
            }
            log.info(
                "Dropping {} partitions older than {} months",
                cp.parentTable(),
                cp.retentionMonths());
            handle.execute(
                "SELECT drop_old_hash_partitions(?::regclass, ?)",
                cp.parentTable(),
                cp.retentionMonths());
          }
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

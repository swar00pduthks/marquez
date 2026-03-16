/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db.migrations;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import marquez.service.PartitionManagementService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Backfills the new partitioned denormalized tables created in V82. This migration runs AFTER V82
 * creates the new partitioned table structure.
 */
@Slf4j
public class V86__BackfillPartitionedDenormalizedTables implements JavaMigration {

  public static int DEFAULT_CHUNK_SIZE = 5000;
  public static int MAX_RUNS_FOR_AUTO_MIGRATION = 100000; // 100K runs limit for automatic migration

  private static final String COUNT_RUNS_SQL = "SELECT COUNT(*) FROM runs";
  private static final String ESTIMATE_COUNT_RUNS_SQL =
      "SELECT reltuples AS cnt FROM pg_class WHERE relname = 'runs'";
  private static final String GET_RUNS_CHUNK_SQL =
      "SELECT uuid FROM runs ORDER BY created_at DESC LIMIT :chunkSize OFFSET :offset";

  @Setter private Integer chunkSize = null;
  @Setter private boolean manual = false;
  @Setter private Jdbi jdbi;

  private PartitionManagementService partitionManagementService;

  public int getChunkSize() {
    return chunkSize != null ? chunkSize : DEFAULT_CHUNK_SIZE;
  }

  @Override
  public MigrationVersion getVersion() {
    return MigrationVersion.fromVersion("86");
  }

  @Override
  public void migrate(Context context) throws Exception {
    log.info("Starting V86: Backfill partitioned denormalized tables created in V82");

    if (context != null) {
      jdbi = Jdbi.create(context.getConnection());
    }

    partitionManagementService = new PartitionManagementService(jdbi, 10, 12);

    int estimatedRunsCount = estimateCountRuns();

    if (estimatedRunsCount < 0) {
      log.info("Vacuuming runs table");
      jdbi.withHandle(h -> h.execute("VACUUM runs;"));
      log.info("Vacuuming runs table finished");
      estimatedRunsCount = estimateCountRuns();
    }

    log.info("Estimating {} runs in runs table", estimatedRunsCount);

    if (estimatedRunsCount == 0 && countRuns() == 0) {
      log.info("Runs table is empty - no data to migrate");
      return;
    }

    if (!manual && estimatedRunsCount >= MAX_RUNS_FOR_AUTO_MIGRATION) {
      log.warn(
          """
              ==================================================
              ==================================================
              ==================================================
              MARQUEZ INSTANCE TOO BIG TO RUN AUTO UPGRADE.
              YOU NEED TO RUN MIGRATION MANUALLY.
              FOR MORE DETAILS, PLEASE REFER TO:
              https://github.com/swar00pduthks/marquez/blob/main/api/src/main/resources/marquez/db/migration/V81-V85__readme.md

              To run manually:
              java -jar marquez-api.jar db-migrate --version v86 ./marquez.yml
              ==================================================
              ==================================================
              ==================================================
              """);
      // We end migration successfully although no data has been migrated to
      // denormalized tables
      return;
    }

    if (estimatedRunsCount > 0) {
      log.info(
          "Starting backfill for {} runs with chunk size {}", estimatedRunsCount, getChunkSize());

      if (estimatedRunsCount > 50000) {
        log.warn(
            "Large dataset detected ({} runs). This migration may take significant time to complete.",
            estimatedRunsCount);
        log.warn(
            "Estimated duration: {} minutes",
            (estimatedRunsCount / 1000)); // Rough estimate: ~1K runs/minute
      }
    }

    // V82 already created empty tables, no need to clear

    log.info("Configured chunkSize is {}", getChunkSize());
    int totalProcessed = 0;
    int totalFailed = 0;
    boolean doMigration = true;

    // Calculate estimated chunks for progress tracking
    int estimatedChunks = (int) Math.ceil((double) estimatedRunsCount / getChunkSize());
    if (estimatedChunks > 1) {
      log.info("Estimated {} chunks to process for {} runs", estimatedChunks, estimatedRunsCount);
    }

    for (int offset = 0; doMigration; offset += getChunkSize()) {
      final int currentOffset = offset;
      List<UUID> runUuids =
          jdbi.withHandle(
              h ->
                  h.createQuery(GET_RUNS_CHUNK_SQL)
                      .bind("chunkSize", getChunkSize())
                      .bind("offset", currentOffset)
                      .mapTo(UUID.class)
                      .list());

      if (runUuids.isEmpty()) {
        doMigration = false;
        break;
      }

      log.info("Processing chunk of {} runs (offset: {})", runUuids.size(), offset);

      int processedInChunk = 0;
      int failedInChunk = 0;
      for (UUID runUuid : runUuids) {
        try {
          populateLineageForRun(runUuid);
          processedInChunk++;
        } catch (Exception e) {
          log.error("Failed to populate lineage for run: {}", runUuid, e);
          failedInChunk++;
          // Continue with other runs even if one fails
        }
      }

      totalProcessed += processedInChunk;
      totalFailed += failedInChunk;

      // Enhanced progress logging for large datasets
      if (estimatedRunsCount > 10000) {
        double progressPercent = (double) totalProcessed / estimatedRunsCount * 100;
        log.info(
            "Processed {} runs in this chunk ({} failed). Total processed: {} ({}%), Total failed: {}",
            processedInChunk,
            failedInChunk,
            totalProcessed,
            String.format("%.1f", progressPercent),
            totalFailed);
      } else {
        log.info(
            "Processed {} runs in this chunk ({} failed). Total processed: {}, Total failed: {}",
            processedInChunk,
            failedInChunk,
            totalProcessed,
            totalFailed);
      }
    }

    log.info(
        "V86 Migration completed. Total runs processed: {}, Total failed: {}",
        totalProcessed,
        totalFailed);
    if (estimatedRunsCount > 10000) {
      log.info(
          "Migration summary: {} runs processed with chunk size {}",
          totalProcessed,
          getChunkSize());
    }
  }

  private void populateLineageForRun(UUID runUuid) {
    jdbi.useTransaction(
        handle -> {
          ensurePartitionExists(handle, runUuid);
          deleteExistingRunRecords(handle, runUuid);
          insertRunLineageDenormalized(handle, runUuid);
          if (isParentRun(handle, runUuid)) {
            insertRunParentLineageDenormalized(handle, runUuid);
          }
        });
  }

  private void ensurePartitionExists(Handle handle, UUID runUuid) {
    String runDateStr =
        handle
            .createQuery("SELECT DATE(ended_at)::text FROM runs WHERE uuid = :runUuid")
            .bind("runUuid", runUuid)
            .mapTo(String.class)
            .findOne()
            .orElse(null);
    if (runDateStr != null) {
      partitionManagementService.ensurePartitionExists(LocalDate.parse(runDateStr));
    }
  }

  private void deleteExistingRunRecords(Handle handle, UUID runUuid) {
    handle
        .createUpdate("DELETE FROM run_lineage_denormalized WHERE run_uuid = :runUuid")
        .bind("runUuid", runUuid)
        .execute();
    handle
        .createUpdate("DELETE FROM run_parent_lineage_denormalized WHERE run_uuid = :runUuid")
        .bind("runUuid", runUuid)
        .execute();
  }

  /**
   * Inserts run lineage using only the V82-era schema columns. input_uuids and output_uuids are
   * added later in V94 and backfilled there.
   */
  private void insertRunLineageDenormalized(Handle handle, UUID runUuid) {
    String sql =
        """
        INSERT INTO run_lineage_denormalized (
            run_uuid, namespace_name, job_name, state, created_at, updated_at,
            started_at, ended_at, job_uuid, job_version_uuid, input_version_uuid,
            input_dataset_uuid, output_version_uuid, output_dataset_uuid,
            input_dataset_namespace, input_dataset_name, input_dataset_version,
            input_dataset_version_uuid, output_dataset_namespace, output_dataset_name,
            output_dataset_version, output_dataset_version_uuid, uuid, parent_run_uuid, run_date
        )
        SELECT DISTINCT
            r.uuid AS run_uuid,
            r.namespace_name,
            r.job_name,
            r.current_run_state AS state,
            r.created_at,
            r.updated_at,
            r.started_at,
            r.ended_at,
            r.job_uuid,
            r.job_version_uuid,
            rim.dataset_version_uuid AS input_version_uuid,
            dvin.dataset_uuid AS input_dataset_uuid,
            dvout.uuid AS output_version_uuid,
            dvout.dataset_uuid AS output_dataset_uuid,
            dvin.namespace_name AS input_dataset_namespace,
            dvin.dataset_name AS input_dataset_name,
            dvin.version AS input_dataset_version,
            dvin.uuid AS input_dataset_version_uuid,
            dvout.namespace_name AS output_dataset_namespace,
            dvout.dataset_name AS output_dataset_name,
            dvout.version AS output_dataset_version,
            dvout.uuid AS output_dataset_version_uuid,
            r.uuid AS uuid,
            r.parent_run_uuid AS parent_run_uuid,
            DATE(r.ended_at) AS run_date
        FROM runs r
        LEFT JOIN runs_input_mapping rim ON rim.run_uuid = r.uuid
        LEFT JOIN dataset_versions dvin ON dvin.uuid = rim.dataset_version_uuid
        LEFT JOIN dataset_versions dvout ON dvout.run_uuid = r.uuid
        WHERE r.uuid = :runUuid AND r.ended_at IS NOT NULL
        """;
    handle.createUpdate(sql).bind("runUuid", runUuid).execute();
  }

  /**
   * Inserts parent run lineage using only the V82-era schema columns. input_uuids and output_uuids
   * are added later in V94 and backfilled there.
   */
  private void insertRunParentLineageDenormalized(Handle handle, UUID runUuid) {
    String sql =
        """
        INSERT INTO run_parent_lineage_denormalized (
            run_uuid, namespace_name, job_name, state, created_at, updated_at,
            started_at, ended_at, job_uuid, job_version_uuid, input_version_uuid,
            input_dataset_uuid, output_version_uuid, output_dataset_uuid,
            input_dataset_namespace, input_dataset_name, input_dataset_version,
            input_dataset_version_uuid, output_dataset_namespace, output_dataset_name,
            output_dataset_version, output_dataset_version_uuid, uuid, parent_run_uuid, run_date
        )
        SELECT DISTINCT
            COALESCE(r.parent_run_uuid, r.uuid) AS run_uuid,
            rp.namespace_name,
            rp.job_name,
            rp.current_run_state AS state,
            rp.created_at,
            rp.updated_at,
            rp.started_at,
            rp.ended_at,
            rp.job_uuid,
            rp.job_version_uuid,
            rim.dataset_version_uuid AS input_version_uuid,
            dvin.dataset_uuid AS input_dataset_uuid,
            dvout.uuid AS output_version_uuid,
            dvout.dataset_uuid AS output_dataset_uuid,
            dvin.namespace_name AS input_dataset_namespace,
            dvin.dataset_name AS input_dataset_name,
            dvin.version AS input_dataset_version,
            dvin.uuid AS input_dataset_version_uuid,
            dvout.namespace_name AS output_dataset_namespace,
            dvout.dataset_name AS output_dataset_name,
            dvout.version AS output_dataset_version,
            dvout.uuid AS output_dataset_version_uuid,
            r.uuid AS uuid,
            r.parent_run_uuid AS parent_run_uuid,
            DATE(rp.ended_at) AS run_date
        FROM runs r
        LEFT JOIN runs_input_mapping rim ON rim.run_uuid = r.uuid
        LEFT JOIN dataset_versions dvin ON dvin.uuid = rim.dataset_version_uuid
        LEFT JOIN dataset_versions dvout ON dvout.run_uuid = r.uuid
        INNER JOIN runs rp ON rp.uuid = r.parent_run_uuid
        WHERE r.parent_run_uuid = :runUuid AND rp.ended_at IS NOT NULL
        """;
    handle.createUpdate(sql).bind("runUuid", runUuid).execute();
  }

  private boolean isParentRun(Handle handle, UUID runUuid) {
    Integer childCount =
        handle
            .createQuery("SELECT COUNT(*) FROM runs WHERE parent_run_uuid = :runUuid")
            .bind("runUuid", runUuid)
            .mapTo(Integer.class)
            .one();
    return childCount > 0;
  }

  @Override
  public String getDescription() {
    return "Backfill partitioned denormalized tables created in V82";
  }

  @Override
  public Integer getChecksum() {
    return null;
  }

  @Override
  public boolean isUndo() {
    return false;
  }

  @Override
  public boolean canExecuteInTransaction() {
    return false;
  }

  @Override
  public boolean isBaselineMigration() {
    return false;
  }

  private int estimateCountRuns() {
    return jdbi.withHandle(h -> h.createQuery(ESTIMATE_COUNT_RUNS_SQL).mapTo(Integer.class).one());
  }

  private int countRuns() {
    return jdbi.withHandle(h -> h.createQuery(COUNT_RUNS_SQL).mapTo(Integer.class).one());
  }
}

/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db.migrations;

import java.util.List;
import java.util.UUID;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import marquez.service.DenormalizedLineageService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
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
      // We end migration successfully although no data has been migrated to denormalized tables
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

    DenormalizedLineageService denormalizedLineageService = new DenormalizedLineageService(jdbi);

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
          denormalizedLineageService.populateLineageForRun(runUuid);
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

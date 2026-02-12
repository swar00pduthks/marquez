/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db.migrations;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.jdbi.v3.core.Jdbi;

@Slf4j
public class V78__BackfillDenormalizedTables implements JavaMigration {

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
    return MigrationVersion.fromVersion("78");
  }

  @Override
  public void migrate(Context context) throws Exception {
    // MIGRATION DISABLED: This migration has been disabled because later migrations
    // (V83+) replace/drop these partition tables. The denormalized tables are now
    // populated on-demand via triggers when lineage events are inserted.
    // Keeping this migration file to maintain Flyway migration history continuity.
    log.info("V78 migration skipped - denormalized tables are now populated via triggers");
    return;

    /* ORIGINAL MIGRATION CODE - COMMENTED OUT
    log.info("Starting migration to populate denormalized lineage tables with existing data");

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
              ==================================================
              ==================================================
              ==================================================
              """);
      // We end migration successfully although no data has been migrated to denormalized tables
      return;
    }

    if (estimatedRunsCount > 0) {
      log.info(
          "Starting migration for {} runs with chunk size {}", estimatedRunsCount, getChunkSize());

      if (estimatedRunsCount > 50000) {
        log.warn(
            "Large dataset detected ({} runs). This migration may take significant time to complete.",
            estimatedRunsCount);
        log.warn(
            "Estimated duration: {} minutes",
            (estimatedRunsCount / 1000)); // Rough estimate: ~1K runs/minute
      }
    }

    // Clear existing data first
    jdbi.withHandle(
        h -> {
          h.execute("DELETE FROM run_lineage_denormalized");
          h.execute("DELETE FROM run_parent_lineage_denormalized");
          log.info("Cleared existing data from denormalized tables");
          return null;
        });

    DenormalizedLineageService denormalizedLineageService = new DenormalizedLineageService(jdbi);

    log.info("Configured chunkSize is {}", getChunkSize());
    int totalProcessed = 0;
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
      for (UUID runUuid : runUuids) {
        try {
          denormalizedLineageService.populateLineageForRun(runUuid);
          processedInChunk++;
        } catch (Exception e) {
          log.error("Failed to populate lineage for run: {}", runUuid, e);
          // Continue with other runs even if one fails
        }
      }

      totalProcessed += processedInChunk;

      // Enhanced progress logging for large datasets
      if (estimatedRunsCount > 10000) {
        double progressPercent = (double) totalProcessed / estimatedRunsCount * 100;
        log.info(
            "Processed {} runs in this chunk. Total processed: {} ({}%)",
            processedInChunk, totalProcessed, String.format("%.1f", progressPercent));
      } else {
        log.info(
            "Processed {} runs in this chunk. Total processed: {}",
            processedInChunk,
            totalProcessed);
      }
    }

    log.info("Migration completed. Total runs processed: {}", totalProcessed);
    if (estimatedRunsCount > 10000) {
      log.info(
          "Migration summary: {} runs processed with chunk size {}",
          totalProcessed,
          getChunkSize());
    }
    */
    // END OF COMMENTED OUT MIGRATION CODE
  }

  @Override
  public String getDescription() {
    return "Populate denormalized lineage tables with existing runs data";
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

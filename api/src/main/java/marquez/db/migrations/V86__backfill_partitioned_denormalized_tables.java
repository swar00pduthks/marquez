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

@Slf4j
public class V86__backfill_partitioned_denormalized_tables implements JavaMigration {

  public static int DEFAULT_CHUNK_SIZE = 50000; // Optimized for large datasets
  private static int BASIC_MIGRATION_LIMIT = 100000; // Skip if > 100K runs

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
    log.info("V86: Backfill partitioned denormalized tables migration invoked");

    if (context != null) {
      jdbi = Jdbi.create(context.getConnection());
    }

    int estimatedRunsCount = estimateCountRuns();

    if (estimatedRunsCount < 0) {
      log.info("Vacuuming runs table for accurate estimates");
      jdbi.withHandle(h -> h.execute("VACUUM runs;"));
      log.info("Vacuuming completed");
      estimatedRunsCount = estimateCountRuns();
    }

    log.info("Estimating {} runs in runs table", estimatedRunsCount);

    if (estimatedRunsCount == 0 && countRuns() == 0) {
      log.info("Runs table is empty - no data to backfill");
      return;
    }

    // Check if this is a large installation that should skip auto-migration
    if (!manual && estimatedRunsCount >= BASIC_MIGRATION_LIMIT) {
      log.warn("");
      log.warn("==================================================");
      log.warn("==================================================");
      log.warn("==================================================");
      log.warn("MARQUEZ INSTANCE TOO BIG TO RUN AUTO BACKFILL.");
      log.warn("Estimated {} runs (threshold: {} runs)", estimatedRunsCount, BASIC_MIGRATION_LIMIT);
      log.warn("");
      log.warn("YOU NEED TO RUN BACKFILL MANUALLY AFTER DEPLOYMENT.");
      log.warn("");
      log.warn("STEPS:");
      log.warn("1. Delete V86 from migration history:");
      log.warn("   DELETE FROM flyway_schema_history WHERE version = '86';");
      log.warn("");
      log.warn("2. Run backfill with optimized chunk size:");
      log.warn("   kubectl exec -it deploy/marquez -n marquez -- sh");
      log.warn("   cd /app");
      log.warn("   java -jar marquez-api.jar db migrate marquez.yml \\");
      log.warn("     -target=86 -placeholders.chunkSize=50000");
      log.warn("");
      log.warn("FOR MORE DETAILS, PLEASE REFER TO:");
      log.warn(
          "https://github.com/MarquezProject/marquez/blob/main/api/src/main/resources/marquez/db/migration/V81-V85__readme.md");
      log.warn("==================================================");
      log.warn("==================================================");
      log.warn("==================================================");
      log.warn("");
      // End migration successfully although no data has been backfilled
      return;
    }

    log.info(
        "Starting backfill for {} runs with chunk size {}", estimatedRunsCount, getChunkSize());

    // Clear existing data first (in case of re-run)
    log.info("Clearing existing denormalized data to ensure clean backfill");
    jdbi.withHandle(
        h -> {
          h.execute("TRUNCATE TABLE run_lineage_denormalized");
          h.execute("TRUNCATE TABLE run_parent_lineage_denormalized");
          log.info("Cleared denormalized tables (ready for fresh backfill)");
          return null;
        });

    DenormalizedLineageService denormalizedLineageService = new DenormalizedLineageService(jdbi);

    log.info("Configured chunkSize: {}", getChunkSize());
    int totalProcessed = 0;
    int totalFailed = 0;
    boolean doContinue = true;

    // Calculate estimated chunks for progress tracking
    int estimatedChunks = (int) Math.ceil((double) estimatedRunsCount / getChunkSize());
    if (estimatedChunks > 1) {
      log.info("Estimated {} chunks to process", estimatedChunks);
    }

    long startTime = System.currentTimeMillis();
    long lastProgressLog = startTime;

    for (int offset = 0; doContinue; offset += getChunkSize()) {
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
        doContinue = false;
        break;
      }

      log.info(
          "Processing chunk {} of ~{} (offset: {}, size: {})",
          (offset / getChunkSize()) + 1,
          estimatedChunks,
          offset,
          runUuids.size());

      int processedInChunk = 0;
      int failedInChunk = 0;

      for (UUID runUuid : runUuids) {
        try {
          // This will populate into the partitioned tables created by V81-V85
          denormalizedLineageService.populateLineageForRun(runUuid);
          processedInChunk++;
        } catch (Exception e) {
          log.error("Failed to backfill lineage for run: {}", runUuid, e);
          failedInChunk++;
          // Continue with next run
        }
      }

      totalProcessed += processedInChunk;
      totalFailed += failedInChunk;

      // Progress logging
      long currentTime = System.currentTimeMillis();
      boolean shouldLogProgress = (currentTime - lastProgressLog) >= 30000; // Every 30 seconds

      if (estimatedRunsCount > 10000 && shouldLogProgress) {
        double progressPercent = (double) totalProcessed / estimatedRunsCount * 100;
        long elapsedSeconds = (currentTime - startTime) / 1000;
        double runsPerSecond = totalProcessed / (double) elapsedSeconds;
        long remainingRuns = estimatedRunsCount - totalProcessed;
        long estimatedSecondsRemaining = (long) (remainingRuns / runsPerSecond);

        log.info(
            "Progress: {} / {} runs ({}%) | Speed: {} runs/sec | Failed: {} | ETA: {} minutes",
            totalProcessed,
            estimatedRunsCount,
            String.format("%.1f", progressPercent),
            String.format("%.1f", runsPerSecond),
            totalFailed,
            estimatedSecondsRemaining / 60);

        lastProgressLog = currentTime;
      } else if (estimatedRunsCount <= 10000) {
        log.info(
            "Processed {} runs in chunk ({} failed). Total: {} / {}",
            processedInChunk,
            failedInChunk,
            totalProcessed,
            estimatedRunsCount);
      }
    }

    long totalSeconds = (System.currentTimeMillis() - startTime) / 1000;

    log.info("===== BACKFILL COMPLETED =====");
    log.info("Total runs processed: {}", totalProcessed);
    log.info("Total failed: {}", totalFailed);
    log.info("Total time: {} minutes {} seconds", totalSeconds / 60, totalSeconds % 60);
    log.info(
        "Average speed: {} runs/second",
        String.format("%.2f", totalProcessed / (double) totalSeconds));

    if (totalFailed > 0) {
      log.warn("Some runs failed to backfill. Check logs above for details.");
      log.warn("Failed runs: {}", totalFailed);
    }

    // Verify final counts
    int denormalizedCount =
        jdbi.withHandle(
            h ->
                h.createQuery("SELECT COUNT(*) FROM run_lineage_denormalized")
                    .mapTo(Integer.class)
                    .one());
    log.info("Final run_lineage_denormalized count: {}", denormalizedCount);
  }

  @Override
  public String getDescription() {
    return "Backfill partitioned denormalized tables created by V81-V85";
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
    return false; // Large data operation, don't use transaction
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

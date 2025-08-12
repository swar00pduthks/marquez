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
public class V78__BackfillDenormalizedTables implements JavaMigration {

  public static int DEFAULT_CHUNK_SIZE = 1000;
  private static int BASIC_MIGRATION_LIMIT = 100000;

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

    if (!manual && estimatedRunsCount >= BASIC_MIGRATION_LIMIT) {
      log.warn(
          """
              ==================================================
              ==================================================
              ==================================================
              MARQUEZ INSTANCE TOO BIG TO RUN AUTO UPGRADE.
              YOU NEED TO RUN v78_migrate COMMAND MANUALLY.
              ESTIMATED RUNS: {}
              ==================================================
              ==================================================
              ==================================================
              """,
          estimatedRunsCount);
      return;
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

      log.info(
          "Processed {} runs in this chunk. Total processed: {}", processedInChunk, totalProcessed);
    }

    log.info("Migration completed. Total runs processed: {}", totalProcessed);
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

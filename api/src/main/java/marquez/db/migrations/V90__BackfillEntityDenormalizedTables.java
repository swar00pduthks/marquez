/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db.migrations;

import lombok.extern.slf4j.Slf4j;
import marquez.service.DenormalizedLineageService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.jdbi.v3.core.Jdbi;

/**
 * Backfills the denormalized entity tables (datasets, dataset versions, and jobs) created in V88.
 * This migration ensures following the entity-level partitioning strategy for improved query
 * performance.
 */
@Slf4j
public class V90__BackfillEntityDenormalizedTables implements JavaMigration {

  private Jdbi jdbi;

  @Override
  public MigrationVersion getVersion() {
    return MigrationVersion.fromVersion("90");
  }

  @Override
  public void migrate(Context context) throws Exception {
    log.info("Starting V90: Backfilling denormalized entity tables created in V88");

    if (context != null) {
      jdbi = Jdbi.create(context.getConnection());
    }

    DenormalizedLineageService denormalizedLineageService = new DenormalizedLineageService(jdbi);

    try {
      // Use the existing service method that handles namespace-level chunking
      denormalizedLineageService.populateAllDenormalizedEntities();
      log.info("V90 Migration completed successfully: All entities backfilled.");
    } catch (Exception e) {
      log.error("Failed to backfill denormalized entities", e);
      throw e;
    }
  }

  @Override
  public String getDescription() {
    return "Backfill denormalized entity tables (datasets, jobs) created in V88";
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
}

/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that the {@code ageEnabled} Flyway placeholder correctly gates the AGE migrations.
 *
 * <p>When {@code ageEnabled=false}:
 *
 * <ul>
 *   <li>V95/V97/V98 are instant no-ops — no WARNINGs, no extension checks
 *   <li>The {@code age} extension is NOT created in the database
 *   <li>All other migrations (schema, denorm tables, views) still apply cleanly
 * </ul>
 *
 * <p>When {@code ageEnabled=true}:
 *
 * <ul>
 *   <li>V95 attempts to create the {@code age} extension (no-op when AGE binary absent)
 *   <li>V97/V98 attempt to create the graph and indexes (no-op when V95 was a no-op)
 *   <li>Migration still succeeds — the SQL guards handle missing AGE gracefully
 * </ul>
 */
@Tag("DataAccessTests")
@Testcontainers
public class AgeEnabledMigrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:14").withReuse(false);

  private DataSource dataSource() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setServerName(POSTGRES.getHost());
    ds.setPortNumber(POSTGRES.getMappedPort(5432));
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    ds.setDatabaseName(POSTGRES.getDatabaseName());
    return ds;
  }

  /**
   * Core contract: {@code ageEnabled=false} must complete all migrations without errors. On vanilla
   * Postgres (no AGE binary) V95/V97/V98 must be silent no-ops.
   */
  @Test
  void migrateWithAgeDisabled_completesWithoutError() {
    assertThatCode(
            () -> DbMigration.migrateDbOrError(new FlywayFactory(), dataSource(), true, false))
        .doesNotThrowAnyException();
  }

  /**
   * Verifies that when {@code ageEnabled=false}, the {@code age} PostgreSQL extension is NOT
   * created — confirming the placeholder gate in V95 worked.
   */
  @Test
  void migrateWithAgeDisabled_ageExtensionNotCreated() throws Exception {
    DbMigration.migrateDbOrError(new FlywayFactory(), dataSource(), true, false);

    // Query pg_extension to confirm 'age' was not created
    try (var conn = dataSource().getConnection();
        var stmt = conn.createStatement();
        var rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_extension WHERE extname = 'age'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1))
          .as("age extension must NOT be installed when ageEnabled=false")
          .isEqualTo(0);
    }
  }

  /**
   * Verifies that core schema tables (datasets, jobs, runs, namespaces) are created even when AGE
   * migrations are gated off — i.e., ageEnabled=false doesn't block anything else.
   */
  @Test
  void migrateWithAgeDisabled_coreTablesExist() throws Exception {
    DbMigration.migrateDbOrError(new FlywayFactory(), dataSource(), true, false);

    try (var conn = dataSource().getConnection();
        var stmt = conn.createStatement()) {
      for (String table :
          new String[] {
            "namespaces", "datasets", "jobs", "runs", "job_denormalized", "dataset_denormalized"
          }) {
        var rs =
            stmt.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_name = '"
                    + table
                    + "'");
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1))
            .as("table '%s' must exist after migration with ageEnabled=false", table)
            .isEqualTo(1);
      }
    }
  }

  /**
   * Verifies that {@code ageEnabled=true} on vanilla Postgres (no AGE binary) also completes
   * without error — the SQL guards in V95/V97/V98 handle missing extension gracefully.
   */
  @Test
  void migrateWithAgeEnabled_onVanillaPostgres_completesWithoutError() {
    assertThatCode(
            () -> DbMigration.migrateDbOrError(new FlywayFactory(), dataSource(), true, true))
        .doesNotThrowAnyException();
  }

  /**
   * Verifies the applied migration count. All versioned migrations (including V95/V97/V98 as
   * no-ops) must appear in flyway_schema_history — none should be skipped/missing.
   */
  @Test
  void migrateWithAgeDisabled_allMigrationsApplied() throws Exception {
    DbMigration.migrateDbOrError(new FlywayFactory(), dataSource(), true, false);

    Flyway flyway = new FlywayFactory().build(dataSource(), false);
    int applied = flyway.info().applied().length;
    assertThat(applied)
        .as("All migrations including V95/V97/V98 no-ops must appear in history")
        .isGreaterThan(90); // we have 95+ versioned migrations
  }
}

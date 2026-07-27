/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.jdbi;

import javax.sql.DataSource;
import org.jdbi.v3.jackson2.Jackson2Plugin;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

public class MarquezJdbiExternalPostgresExtension extends JdbiExternalPostgresExtension {

  // Set MARQUEZ_TEST_PG_HOST to run the integration tests against an already-running PostgreSQL
  // instead of a Testcontainers-managed one. This lets the suite run where Docker is unavailable
  // (e.g. sandboxes / CI runners without a Docker daemon). Unset — the default, including CI —
  // boots a throwaway postgres:15.4 container exactly as before. The target DB is CLEANed and
  // re-migrated by Flyway on each test class, so point it at a dedicated throwaway database.
  //   MARQUEZ_TEST_PG_HOST (enables external mode), MARQUEZ_TEST_PG_PORT (default 5432),
  //   MARQUEZ_TEST_PG_DB (default marquez_test), MARQUEZ_TEST_PG_USER (default postgres),
  //   MARQUEZ_TEST_PG_PASSWORD (default empty).
  private static final String EXTERNAL_PG_HOST = System.getenv("MARQUEZ_TEST_PG_HOST");
  private static final boolean USE_EXTERNAL_PG =
      EXTERNAL_PG_HOST != null && !EXTERNAL_PG_HOST.isBlank();

  private static final PostgreSQLContainer<?> POSTGRES =
      USE_EXTERNAL_PG ? null : new PostgreSQLContainer<>("postgres:15.4");

  static {
    if (!USE_EXTERNAL_PG) {
      POSTGRES.start();
    }
  }

  private final String hostname;
  private final Integer port;
  private final String username;
  private final String password;
  private final String database;

  public MarquezJdbiExternalPostgresExtension() {
    super();
    if (USE_EXTERNAL_PG) {
      hostname = EXTERNAL_PG_HOST;
      port = Integer.parseInt(envOrDefault("MARQUEZ_TEST_PG_PORT", "5432"));
      username = envOrDefault("MARQUEZ_TEST_PG_USER", "postgres");
      password = envOrDefault("MARQUEZ_TEST_PG_PASSWORD", "");
      database = envOrDefault("MARQUEZ_TEST_PG_DB", "marquez_test");
    } else {
      hostname = POSTGRES.getHost();
      port = POSTGRES.getMappedPort(5432);
      username = POSTGRES.getUsername();
      password = POSTGRES.getPassword();
      database = POSTGRES.getDatabaseName();
    }

    // Add required plugins
    super.plugins.add(new SqlObjectPlugin());
    super.plugins.add(new PostgresPlugin());
    super.plugins.add(new Jackson2Plugin());

    // Configure migration
    super.migration =
        Migration.before().withPaths("marquez/db/migration", "classpath:marquez/db/migrations");
  }

  private static String envOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return (value != null && !value.isBlank()) ? value : fallback;
  }

  @Override
  protected DataSource createDataSource() {
    final PGSimpleDataSource datasource = new PGSimpleDataSource();
    datasource.setServerName(hostname);
    datasource.setPortNumber(port);
    datasource.setUser(username);
    datasource.setPassword(password);
    datasource.setDatabaseName(database);
    datasource.setApplicationName("Marquez Unit Tests");
    return datasource;
  }
}

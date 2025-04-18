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

  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15.4");

  static {
    POSTGRES.start();
  }

  private final String hostname;
  private final Integer port;
  private final String username;
  private final String password;
  private final String database;

  public MarquezJdbiExternalPostgresExtension() {
    super();
    hostname = POSTGRES.getHost();
    port = POSTGRES.getMappedPort(5432);
    username = POSTGRES.getUsername();
    password = POSTGRES.getPassword();
    database = POSTGRES.getDatabaseName();

    // Add required plugins
    super.plugins.add(new SqlObjectPlugin());
    super.plugins.add(new PostgresPlugin());
    super.plugins.add(new Jackson2Plugin());

    // Configure migration
    super.migration =
        Migration.before().withPaths("marquez/db/migration", "classpath:marquez/db/migrations");
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

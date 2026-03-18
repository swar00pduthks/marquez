/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez;

import java.util.HashMap;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

public final class PostgresContainer extends PostgreSQLContainer<PostgresContainer> {
  private static final int JDBC = 5;

  private static final Map<String, PostgresContainer> containers = new HashMap<>();

  private String host;
  private int port;

  private PostgresContainer() {
    super(new ImageFromDockerfile("marquez-postgres-age-test", true)
        .withFileFromString("Dockerfile",
            "FROM postgres:14\n" +
            "RUN apt-get update && apt-get install -y build-essential git postgresql-server-dev-14 flex bison && rm -rf /var/lib/apt/lists/*\n" +
            "RUN git clone -b PG14 --depth 1 https://github.com/apache/age.git /tmp/age && cd /tmp/age && make && make install\n" +
            "RUN echo \"CREATE EXTENSION IF NOT EXISTS age;\" > /docker-entrypoint-initdb.d/00_init_age.sql \\\n" +
            "    && echo \"LOAD 'age';\" >> /docker-entrypoint-initdb.d/00_init_age.sql \\\n" +
            "    && echo \"SET search_path = ag_catalog, \\\"$$user\\\", public;\" >> /docker-entrypoint-initdb.d/00_init_age.sql"
        ).get());
    // Provide substitute tag natively
    this.setDockerImageName("postgres:14");
  }

  public static PostgresContainer create(String name) {
    PostgresContainer container = containers.get(name);
    if (container == null) {
      container = new PostgresContainer();
      containers.put(name, container);
    }
    return container;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  @Override
  public void start() {
    super.start();

    host = super.getHost();
    port = super.getFirstMappedPort();
  }

  @Override
  public void stop() {}
}

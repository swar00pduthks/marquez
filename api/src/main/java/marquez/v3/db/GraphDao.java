/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Data access object for Apache AGE graph operations.
 *
 * <p>Uses raw JDBC (not JDBI) for all Cypher queries because JDBI's SQL parser does not understand
 * PostgreSQL $$ dollar-quoting and incorrectly interprets Cypher label syntax (e.g., :Namespace,
 * :Job) as named parameters. This follows the official AGE JDBC driver pattern.
 *
 * @see <a href="https://github.com/apache/age/tree/master/drivers/jdbc">AGE JDBC Driver</a>
 */
@Slf4j
public class GraphDao {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Initializes an AGE session on a raw JDBC connection. */
  public static void initAgeSession(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("LOAD 'age'");
      stmt.execute("SET search_path = ag_catalog, \"$user\", public");
    }
  }

  /** Creates an agtype-typed PGobject for use as a PreparedStatement parameter. */
  public static org.postgresql.util.PGobject createAgtype(String json) throws SQLException {
    org.postgresql.util.PGobject obj = new org.postgresql.util.PGobject();
    obj.setType("agtype");
    obj.setValue(json);
    return obj;
  }

  public void initGraph(Jdbi jdbi, String graphName) {
    jdbi.useHandle(
        handle -> {
          try {
            Connection conn = handle.getConnection();
            if (conn != null) {
              initAgeSession(conn);
              try (Statement stmt = conn.createStatement()) {
                var rs =
                    stmt.executeQuery(
                        "SELECT 1 FROM ag_graph WHERE name = '"
                            + graphName.replace("'", "''")
                            + "'");
                if (!rs.next()) {
                  stmt.execute("SELECT create_graph('" + graphName.replace("'", "''") + "')");
                }
              }
              createIndices(conn, graphName);
            }
          } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize graph: " + graphName, e);
          }
        });
  }

  private void createIndices(Connection conn, String graphName) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      String[] labels = {
        "Namespace", "Job", "JobVersion", "Run", "RunState", "Dataset", "DatasetVersion", "Source"
      };
      String gn = "\"" + graphName.replace("\"", "\"\"") + "\"";

      for (String label : labels) {
        try {
          // Create GIN index on properties for best overall performance at scale
          stmt.execute(
              String.format(
                  "CREATE INDEX IF NOT EXISTS idx_age_%s_props ON %s.\"%s\" USING GIN (properties)",
                  label.toLowerCase(), gn, label));
        } catch (SQLException e) {
          log.warn("Failed to create index for label {}: {}", label, e.getMessage());
        }
      }
    }
  }

  public void upsertNode(
      Handle handle,
      String graphName,
      String label,
      String matchKey,
      Map<String, Object> properties)
      throws SQLException {
    // Stringify values to ensure AGE compatibility and build literals
    Map<String, Object> stringifiedProps = new HashMap<>();
    for (Map.Entry<String, Object> entry : properties.entrySet()) {
      Object val = entry.getValue();
      if (val instanceof java.time.Instant) {
        stringifiedProps.put(entry.getKey(), val.toString());
      } else {
        stringifiedProps.put(entry.getKey(), val);
      }
    }

    String matchValueLiteral = toCypherLiteral(stringifiedProps.get(matchKey));
    String cypherProps = toCypherMap(stringifiedProps);

    Connection conn = handle.getConnection();
    if (conn != null) {
      initAgeSession(conn);

      String sql =
          String.format(
              "SELECT * FROM ag_catalog.cypher(cast('%s' as name), $$ MERGE (n:%s { %s: %s }) SET n = %s RETURN n $$) as (n agtype)",
              graphName, label, matchKey, matchValueLiteral, cypherProps);

      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.execute();
      }
    }
  }

  private String toCypherLiteral(Object val) {
    if (val == null) {
      return "null";
    }
    if (val instanceof Number || val instanceof Boolean) {
      return val.toString();
    }
    if (val instanceof String) {
      return "'" + val.toString().replace("'", "''") + "'";
    }
    try {
      return "'" + MAPPER.writeValueAsString(val).replace("'", "''") + "'";
    } catch (Exception e) {
      return "'" + val.toString().replace("'", "''") + "'";
    }
  }

  private String toCypherMap(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      sb.append(entry.getKey()).append(": ").append(toCypherLiteral(entry.getValue()));
      first = false;
    }
    sb.append("}");
    return sb.toString();
  }

  public void upsertEdge(
      Handle handle,
      String graphName,
      String edgeLabel,
      String fromLabel,
      String fromMatchKey,
      String fromMatchValue,
      String toLabel,
      String toMatchKey,
      String toMatchValue)
      throws SQLException {

    Connection conn = handle.getConnection();
    if (conn != null) {
      initAgeSession(conn);

      String sql =
          String.format(
              "SELECT * FROM ag_catalog.cypher(cast('%s' as name), $$ MATCH (a:%s { %s: %s }) MATCH (b:%s { %s: %s }) MERGE (a)-[r:%s]->(b) RETURN r $$) as (r agtype)",
              graphName,
              fromLabel,
              fromMatchKey,
              toCypherLiteral(fromMatchValue),
              toLabel,
              toMatchKey,
              toCypherLiteral(toMatchValue),
              edgeLabel);

      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.execute();
      }
    }
  }
}

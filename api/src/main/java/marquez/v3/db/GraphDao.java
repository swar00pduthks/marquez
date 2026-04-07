/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Data access object for Apache AGE graph operations.
 *
 * <p>Uses raw JDBC (not JDBI) for all Cypher queries because JDBI's SQL parser does not understand
 * PostgreSQL {@code $$} dollar-quoting and incorrectly interprets Cypher label syntax (e.g., {@code
 * :Namespace}, {@code :Job}) as named parameters.
 *
 * <p><strong>Session initialisation:</strong> {@link #initAgeSession(Connection)} must be called
 * <em>once</em> per JDBC connection before issuing any Cypher statement. It is intentionally
 * <em>not</em> called inside {@link #upsertNode} or {@link #upsertEdge} to avoid executing {@code
 * LOAD 'age'} and {@code SET search_path} on every individual operation.
 *
 * @see <a href="https://github.com/apache/age/tree/master/drivers/jdbc">AGE JDBC Driver</a>
 */
@Slf4j
public class GraphDao {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SCHEMA_PREFIX = "ag_catalog.";

  /**
   * Thread-safe flag indicating whether the Apache AGE extension is available on the connected
   * PostgreSQL instance. Set once during {@link #initAgeSession(Connection)}; read by all
   * subsequent operations.
   */
  private static final AtomicBoolean AGE_AVAILABLE = new AtomicBoolean(false);

  // ---------------------------------------------------------------------------
  // Session / availability
  // ---------------------------------------------------------------------------

  /**
   * Initialises an AGE session on the given JDBC connection.
   *
   * <p>Call this <em>once</em> per {@link Handle} / connection before executing any Cypher
   * statements. The method is idempotent – calling it multiple times on the same connection is
   * harmless but wasteful.
   *
   * @param conn an open JDBC connection
   */
  public static void initAgeSession(Connection conn) {
    // First call: detect whether AGE is available at all.
    if (!AGE_AVAILABLE.get()) {
      detectAge(conn);
    }

    if (!AGE_AVAILABLE.get()) {
      return;
    }

    // Always run LOAD 'age' per connection.
    // On Azure, AGE is in shared_preload_libraries so LOAD is a no-op (or blocked by policy
    // and silently ignored via SAVEPOINT).  On plain Postgres / test containers it is required
    // for every new connection — skipping it causes "unhandled cypher(cstring) function call".
    boolean wasAutoCommit = true;
    try {
      wasAutoCommit = conn.getAutoCommit();
      if (!wasAutoCommit) {
        try (Statement sp = conn.createStatement()) {
          sp.execute("SAVEPOINT age_session_sp");
        }
      }
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("LOAD 'age'");
      } catch (Exception ignored) {
        // Already loaded (shared_preload_libraries) or blocked by policy — either way AGE works.
        if (!wasAutoCommit) {
          try (Statement sp = conn.createStatement()) {
            sp.execute("ROLLBACK TO SAVEPOINT age_session_sp");
          } catch (Exception ignored2) {
          }
        }
      } finally {
        if (!wasAutoCommit) {
          try (Statement sp = conn.createStatement()) {
            sp.execute("RELEASE SAVEPOINT age_session_sp");
          } catch (Exception ignored) {
          }
        }
      }
    } catch (Exception e) {
      log.debug("initAgeSession LOAD note: {}", e.getMessage());
    }

    try (Statement stmt = conn.createStatement()) {
      // Include marquez_v3 schema so agtype_to_json() is found (it lives there on Azure).
      stmt.execute("SET search_path = ag_catalog, marquez_v3, \"$user\", public");
    } catch (Exception e) {
      log.debug("SET search_path note: {}", e.getMessage());
    }
  }

  /**
   * Detects whether Apache AGE is available. Called once; result cached in {@link #AGE_AVAILABLE}.
   * Uses a SAVEPOINT to isolate {@code LOAD 'age'} failures so that an active transaction is not
   * aborted when the command is blocked (e.g. on Azure Flexible Server).
   */
  private static void detectAge(Connection conn) {
    boolean wasAutoCommit = true;
    try {
      wasAutoCommit = conn.getAutoCommit();
      // Only use savepoints inside an explicit transaction
      if (!wasAutoCommit) {
        try (Statement sp = conn.createStatement()) {
          sp.execute("SAVEPOINT age_load_sp");
        }
      }

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("LOAD 'age'");
        AGE_AVAILABLE.set(true);
        log.info("Apache AGE loaded successfully.");
      } catch (Exception loadEx) {
        // Roll back to savepoint so the enclosing transaction is not aborted
        if (!wasAutoCommit) {
          try (Statement sp = conn.createStatement()) {
            sp.execute("ROLLBACK TO SAVEPOINT age_load_sp");
          } catch (Exception ignored) {
          }
        }
        // LOAD failed (access denied or already loaded) — check pg_extension
        try (Statement stmt = conn.createStatement();
            var rs = stmt.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'age'")) {
          boolean found = rs.next();
          AGE_AVAILABLE.set(found);
          if (found) {
            log.info(
                "Note: LOAD 'age' failed, but AGE extension is present: {}", loadEx.getMessage());
          } else {
            log.warn("Apache AGE extension not found — V3 graph features disabled.");
          }
        } catch (Exception checkEx) {
          log.warn("Failed to check for AGE extension: {}", checkEx.getMessage());
        }
      } finally {
        if (!wasAutoCommit) {
          try (Statement sp = conn.createStatement()) {
            sp.execute("RELEASE SAVEPOINT age_load_sp");
          } catch (Exception ignored) {
          }
        }
      }
    } catch (Exception e) {
      log.warn("Failed to initialise AGE session: {}", e.getMessage());
    }
  }

  /** Returns the {@code ag_catalog.} schema prefix when AGE is available, otherwise {@code ""}. */
  public static String prefix() {
    return AGE_AVAILABLE.get() ? SCHEMA_PREFIX : "";
  }

  /** Returns {@code true} if the Apache AGE extension was detected on the connected instance. */
  public static boolean isAgeAvailable() {
    return AGE_AVAILABLE.get();
  }

  // ---------------------------------------------------------------------------
  // Graph initialisation
  // ---------------------------------------------------------------------------

  /**
   * Creates the named graph and its BTREE lookup indexes if they do not already exist.
   *
   * @param jdbi a configured {@link Jdbi} instance
   * @param graphName the AGE graph name (e.g., {@code "marquez_graph"})
   */
  public void initGraph(Jdbi jdbi, String graphName) {
    jdbi.useHandle(
        handle -> {
          try {
            Connection conn = handle.getConnection();
            initAgeSession(conn);
            if (!AGE_AVAILABLE.get()) {
              log.warn("Apache AGE not available – skipping graph init for '{}'.", graphName);
              return;
            }
            try (Statement stmt = conn.createStatement()) {
              String safeName = graphName.replace("'", "''");
              try (var rs =
                  stmt.executeQuery("SELECT 1 FROM ag_graph WHERE name = '" + safeName + "'")) {
                if (!rs.next()) {
                  stmt.execute("SELECT create_graph('" + safeName + "')");
                  log.info("Created AGE graph '{}'.", graphName);
                }
              }
              // Indexes are managed by Flyway V98 migration (BTREE on agtype properties).
              // Do NOT call createIndexes() here — agtype expression indexes cannot be
              // compiled via plain JDBC; V98 uses PL/pgSQL EXECUTE which resolves AGE
              // operators at runtime.
            }
          } catch (SQLException e) {
            log.error(
                "Failed to initialise AGE graph '{}'. V3 features may be disabled.", graphName, e);
          }
        });
  }

  /**
   * Creates BTREE expression indexes on node properties for all known labels. These replace the GIN
   * indexes that are ineffective for Cypher equality-predicate lookups.
   */
  private void createIndexes(Connection conn, String graphName) throws SQLException {
    // label -> array of property keys to index
    String[][] labelKeys = {
      {"Job", "fqn"},
      {"Dataset", "fqn"},
      {"JobVersion", "uuid"},
      {"DatasetVersion", "uuid", "datasetFqn"},
      {"Run", "runId", "state"},
      {"Namespace", "name"},
      {"Source", "name"},
    };

    String gn = "\"" + graphName.replace("\"", "\"\"") + "\"";
    try (Statement stmt = conn.createStatement()) {
      for (String[] entry : labelKeys) {
        String label = entry[0];
        for (int i = 1; i < entry.length; i++) {
          String key = entry[i];
          String idxName = "idx_age_" + label.toLowerCase() + "_" + key.toLowerCase();
          try {
            stmt.execute(
                String.format(
                    "CREATE INDEX IF NOT EXISTS %s ON %s.\"%s\" ((properties->>'%s'))",
                    idxName, gn, label, key));
          } catch (SQLException e) {
            log.warn(
                "Could not create index {} on {}.{}: {}",
                idxName,
                graphName,
                label,
                e.getMessage());
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Node / edge upsert
  // ---------------------------------------------------------------------------

  /**
   * Upserts a node in the graph using a {@code MERGE} on {@code matchKey}.
   *
   * <p><strong>Pre-condition:</strong> {@link #initAgeSession(Connection)} must have been called on
   * the connection backing the supplied {@code handle} before this method is invoked.
   *
   * @param handle JDBI handle whose underlying connection has an active AGE session
   * @param graphName target graph name
   * @param label Cypher node label (e.g., {@code "Job"})
   * @param matchKey property name used in the {@code MERGE} predicate
   * @param properties all properties to set on the node
   */
  public void upsertNode(
      Handle handle,
      String graphName,
      String label,
      String matchKey,
      Map<String, Object> properties)
      throws SQLException {

    Map<String, Object> strProps = stringify(properties);
    String matchValueLiteral = toCypherLiteral(strProps.get(matchKey));

    // AGE 1.5.0 bug: MERGE + SET in the same Cypher statement fails with
    // "Entity failed to be updated: 3" (PostgreSQL TM_Updated) when the node
    // already exists. Split into two statements to avoid this:
    //   1. MERGE (no SET) — idempotently creates the node if absent.
    //   2. MATCH ... SET n.k=v, ... — updates individual properties on the node.
    // Individual-property SET on a committed tuple (from statement 1) returns TM_Ok.

    // Statement 1: ensure node exists
    String mergeSql =
        String.format(
            "SELECT * FROM %scypher(cast('%s' as name), $$ MERGE (n:%s {%s: %s}) RETURN n $$) as (n %sagtype)",
            prefix(), graphName, label, matchKey, matchValueLiteral, prefix());

    // Statement 2: set individual properties
    String setAssignments = toCypherSetAssignments("n", strProps);
    String setSql =
        String.format(
            "SELECT * FROM %scypher(cast('%s' as name), $$ MATCH (n:%s {%s: %s}) SET %s RETURN n $$) as (n %sagtype)",
            prefix(), graphName, label, matchKey, matchValueLiteral, setAssignments, prefix());

    Connection conn = handle.getConnection();
    // Statement 1: ensure node exists (MERGE with no SET — safe for both new and existing nodes)
    try (Statement stmt = conn.createStatement()) {
      stmt.execute(mergeSql);
    }
    // Statement 2: set individual properties.
    // AGE 1.5.0 bug: updating an EXISTING node via Cypher SET throws "Entity failed to be updated:
    // 3".
    // The bug only affects existing nodes; newly-created nodes can be updated immediately after
    // MERGE.
    // We suppress the AGE error for existing nodes — their properties were already set on initial
    // creation and Job/Dataset properties are stable (name, namespace, fqn do not change).
    try (Statement stmt = conn.createStatement()) {
      stmt.execute(setSql);
    } catch (SQLException e) {
      if (e.getMessage() != null && e.getMessage().contains("Entity failed to be updated")) {
        log.debug(
            "AGE 1.5.0: skipping property update on existing {} node '{}' (properties unchanged): {}",
            label,
            properties.get(matchKey),
            e.getMessage());
      } else {
        throw e;
      }
    }
  }

  /**
   * Upserts a directed edge between two existing nodes using a {@code MERGE} on the relationship
   * type.
   *
   * <p><strong>Pre-condition:</strong> {@link #initAgeSession(Connection)} must have been called on
   * the connection backing the supplied {@code handle} before this method is invoked.
   *
   * @param handle JDBI handle whose underlying connection has an active AGE session
   * @param graphName target graph name
   * @param edgeLabel Cypher relationship type (e.g., {@code "PRODUCES"})
   * @param fromLabel label of the source node
   * @param fromMatchKey property used to identify the source node
   * @param fromMatchValue value of the source node's match property
   * @param toLabel label of the target node
   * @param toMatchKey property used to identify the target node
   * @param toMatchValue value of the target node's match property
   */
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

    String sql =
        String.format(
            "SELECT * FROM %scypher(cast('%s' as name), $$ MATCH (a:%s { %s: %s }) MATCH (b:%s { %s: %s }) MERGE (a)-[r:%s]->(b) RETURN r $$) as (r %sagtype)",
            prefix(),
            graphName,
            fromLabel,
            fromMatchKey,
            toCypherLiteral(fromMatchValue),
            toLabel,
            toMatchKey,
            toCypherLiteral(toMatchValue),
            edgeLabel,
            prefix());

    Connection conn = handle.getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.execute();
    }
  }

  /**
   * Upserts a directed edge with additional properties on the edge itself (e.g. {@code
   * transformationType} on a {@code DERIVED_FROM} edge). Properties are set via {@code ON CREATE
   * SET} / {@code SET} after the MERGE.
   *
   * <p>AGE 1.5.0 does not support {@code ON CREATE SET} syntax, so we fall back to a post-MERGE
   * {@code SET} which overwrites on every upsert — acceptable for immutable provenance properties.
   */
  public void upsertEdgeWithProps(
      Handle handle,
      String graphName,
      String edgeLabel,
      String fromLabel,
      String fromMatchKey,
      String fromMatchValue,
      String toLabel,
      String toMatchKey,
      String toMatchValue,
      Map<String, Object> edgeProps)
      throws SQLException {

    if (edgeProps == null || edgeProps.isEmpty()) {
      upsertEdge(
          handle,
          graphName,
          edgeLabel,
          fromLabel,
          fromMatchKey,
          fromMatchValue,
          toLabel,
          toMatchKey,
          toMatchValue);
      return;
    }

    StringBuilder setClause = new StringBuilder();
    for (String key : edgeProps.keySet()) {
      if (setClause.length() > 0) setClause.append(", ");
      Object val = edgeProps.get(key);
      setClause
          .append("r.")
          .append(key)
          .append(" = ")
          .append(val instanceof String ? "'" + ((String) val).replace("'", "\\'") + "'" : val);
    }

    String sql =
        String.format(
            "SELECT * FROM %scypher(cast('%s' as name), $$ "
                + "MATCH (a:%s { %s: %s }) MATCH (b:%s { %s: %s }) "
                + "MERGE (a)-[r:%s]->(b) SET %s RETURN r "
                + "$$) as (r %sagtype)",
            prefix(),
            graphName,
            fromLabel,
            fromMatchKey,
            toCypherLiteral(fromMatchValue),
            toLabel,
            toMatchKey,
            toCypherLiteral(toMatchValue),
            edgeLabel,
            setClause,
            prefix());

    Connection conn = handle.getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.execute();
    }
  }

  // ---------------------------------------------------------------------------
  // AGE parameter helpers
  // ---------------------------------------------------------------------------

  /**
   * Creates an {@code agtype}-typed {@link org.postgresql.util.PGobject} from a JSON string. Used
   * to pass parameters to parameterised Cypher queries.
   *
   * @param json a valid JSON string
   * @return a PGobject with type {@code "agtype"}
   */
  public static org.postgresql.util.PGobject createAgtype(String json) throws SQLException {
    org.postgresql.util.PGobject obj = new org.postgresql.util.PGobject();
    obj.setType("agtype");
    obj.setValue(json);
    return obj;
  }

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  /** Converts {@link java.time.Instant} values to strings; leaves others unchanged. */
  private static Map<String, Object> stringify(Map<String, Object> props) {
    java.util.Map<String, Object> out = new java.util.HashMap<>();
    for (Map.Entry<String, Object> e : props.entrySet()) {
      Object val = e.getValue();
      out.put(e.getKey(), val instanceof java.time.Instant ? val.toString() : val);
    }
    return out;
  }

  /** Renders a Java value as a Cypher literal (string, number, boolean, or null). */
  static String toCypherLiteral(Object val) {
    if (val == null) return "null";
    if (val instanceof Number || val instanceof Boolean) return val.toString();
    if (val instanceof String) return "'" + ((String) val).replace("'", "''") + "'";
    try {
      return "'" + MAPPER.writeValueAsString(val).replace("'", "''") + "'";
    } catch (Exception e) {
      return "'" + val.toString().replace("'", "''") + "'";
    }
  }

  /**
   * Renders individual SET assignments for a node variable, e.g., {@code n.name = 'foo', n.size =
   * 3}. Used in {@code MATCH (n:Label {...}) SET n.k=v, ...} statements to avoid the AGE 1.5.0
   * MERGE+SET double-update bug.
   */
  static String toCypherSetAssignments(String nodeVar, Map<String, Object> map) {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Map.Entry<String, Object> e : map.entrySet()) {
      if (!first) sb.append(", ");
      sb.append(nodeVar)
          .append(".")
          .append(e.getKey())
          .append(" = ")
          .append(toCypherLiteral(e.getValue()));
      first = false;
    }
    return sb.toString();
  }

  /** Renders a property map as a Cypher map literal, e.g., {@code {name: 'foo', size: 3}}. */
  static String toCypherMap(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> e : map.entrySet()) {
      if (!first) sb.append(", ");
      sb.append(e.getKey()).append(": ").append(toCypherLiteral(e.getValue()));
      first = false;
    }
    sb.append("}");
    return sb.toString();
  }
}

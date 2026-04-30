/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import marquez.v3.db.GraphDao;
import org.jdbi.v3.core.Jdbi;

/**
 * Highly optimized Pure-Cypher graph endpoint that delegates 100% of JSON mapping to the Apache AGE
 * PostgreSQL extension, bypassing JVM Object initialization to prevent Heap OOM risks and minimize
 * App-level execution latency.
 */
@Slf4j
@Path("/api/v3beta/lineage")
@Produces(MediaType.APPLICATION_JSON)
public class OpenLineageResourceV3Beta {
  private final Jdbi jdbi;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public OpenLineageResourceV3Beta(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getLineageV3Beta(
      @QueryParam("nodeId") String nodeId,
      @QueryParam("depth") Integer depth,
      @QueryParam("edgeType") String edgeType,
      @QueryParam("aggregateToParentRun") Boolean aggregateToParentRun) {

    if (nodeId == null || nodeId.isEmpty()) {
      return Response.status(Response.Status.BAD_REQUEST).entity("nodeId is required").build();
    }

    int d = depth == null ? 2 : depth;
    boolean aggregate = aggregateToParentRun != null && aggregateToParentRun;

    String fqn = nodeId;
    String lowerId = nodeId.toLowerCase();
    if (lowerId.startsWith("dataset:")) {
      fqn = nodeId.substring(8);
    } else if (lowerId.startsWith("job:")) {
      fqn = nodeId.substring(4);
    }

    Map<String, Object> params = new HashMap<>();
    params.put("fqn", fqn);
    params.put("agg", aggregate);

    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(params);
    } catch (Exception e) {
      log.error("Failed to serialize parameters", e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    log.info(
        "Fetching v3beta ({}) lineage for fqn: {}, depth: {}",
        edgeType != null ? edgeType : "ALL",
        fqn,
        d);

    // Pure Cypher Path Extraction
    // Defensive CASE for path IS NULL to prevent 500 errors in list comprehension
    // Case sensitivity: FQN resolution is now case-insensitive for prefixes.
    String sql =
        String.format(
            "SELECT %s FROM %scypher(cast('marquez_graph' as name), $$ "
                + "MATCH (n) WHERE n.fqn = $fqn "
                + "OPTIONAL MATCH path = (n)-[:INPUT_TO|OUTPUT_FROM*1..%d]-(m) "
                + "UNWIND (CASE WHEN path IS NULL THEN [null] ELSE relationships(path) END) as rel "
                + "WITH n, m, rel "
                + "WHERE rel IS NULL OR '%s' = '' OR type(rel) = '%s' "
                + "WITH collect(distinct n) as ns1, collect(distinct m) as ns2, collect(distinct rel) as rs "
                + "WITH ns1 + ns2 as ns_raw, rs "
                + "UNWIND ns_raw as nd "
                + "WITH nd, rs WHERE nd IS NOT NULL "
                + "WITH collect(distinct nd) as final_nodes, rs "
                + "UNWIND (CASE WHEN rs = [] THEN [null] ELSE rs END) as r "
                + "WITH final_nodes, r WHERE r IS NOT NULL "
                + "WITH final_nodes, collect(distinct r) as final_edges "
                + "RETURN { "
                + "    nodes: final_nodes, "
                + "    edges: final_edges "
                + "} AS result "
                + "$$, ?) as (result %sagtype)",
            // agtype_to_json lives in marquez_v3 (created by V103), NOT ag_catalog.
            "marquez_v3.agtype_to_json(result)",
            GraphDao.prefix(),
            d,
            edgeType != null ? edgeType : "",
            edgeType != null ? edgeType : "",
            GraphDao.prefix());

    try {
      JsonNode result =
          jdbi.withHandle(
              handle -> {
                try {
                  Connection conn = handle.getConnection();
                  GraphDao.initAgeSession(conn);
                  try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setObject(1, GraphDao.createAgtype(paramsJson));
                    try (ResultSet rs = ps.executeQuery()) {
                      if (rs.next()) {
                        String rawJson = rs.getString(1);
                        return rawJson != null ? MAPPER.readTree(rawJson) : null;
                      }
                    }
                  }
                  return null;
                } catch (Exception e) {
                  throw new RuntimeException("Cypher query failed", e);
                }
              });

      if (result == null) {
        return Response.status(Response.Status.NOT_FOUND).build();
      }

      // Return the raw ObjectNode directly!
      return Response.ok(Map.of("graph", result)).build();

    } catch (Exception e) {
      log.error("Cypher query failed for v3beta lineage", e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
  }
}

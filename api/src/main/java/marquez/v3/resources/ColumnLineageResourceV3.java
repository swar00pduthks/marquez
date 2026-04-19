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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import marquez.v3.db.GraphDao;
import org.jdbi.v3.core.Jdbi;

@Path("/api/v3/column-lineage")
@Produces(MediaType.APPLICATION_JSON)
public class ColumnLineageResourceV3 {
  private final Jdbi jdbi;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public ColumnLineageResourceV3(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @GET
  public Response getLineage(
      @QueryParam("nodeId") String nodeId, @QueryParam("depth") Integer depth) {
    int d = depth == null ? 10 : depth;
    if (d > 20) {
      d = 20; // Hard upper bound to prevent traversal DoS attacks
    }
    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(Collections.singletonMap("nodeId", nodeId));
    } catch (Exception e) {
      return Response.status(Response.Status.BAD_REQUEST).entity("Invalid nodeId").build();
    }

    // Follow the DERIVED_FROM edges to track column lineage across dataset fields
    String sql =
        String.format(
            "SELECT agtype_to_json(path) FROM %scypher('marquez_graph', $$ "
                + "MATCH path = (a:DatasetField)-[:DERIVED_FROM*1..%d]-(b:DatasetField) "
                + "WHERE a.id = $nodeId RETURN path "
                + "$$, ?) as (path %sagtype)",
            GraphDao.prefix(), GraphDao.prefix(), d, GraphDao.prefix());

    List<JsonNode> result =
        jdbi.withHandle(
            handle -> {
              try {
                List<JsonNode> rows = new ArrayList<>();
                Connection conn = handle.getConnection();
                GraphDao.initAgeSession(conn);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                  ps.setObject(1, GraphDao.createAgtype(paramsJson));
                  try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                      rows.add(MAPPER.readTree(rs.getString(1)));
                    }
                  }
                }
                return rows;
              } catch (Exception e) {
                throw new RuntimeException("Cypher query failed", e);
              }
            });

    return Response.ok(Map.of("lineage", result)).build();
  }
}

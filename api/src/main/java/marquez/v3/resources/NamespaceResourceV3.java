/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import marquez.v3.db.GraphDao;
import org.jdbi.v3.core.Jdbi;

@Path("/api/v3/namespaces")
@Produces(MediaType.APPLICATION_JSON)
public class NamespaceResourceV3 {

  private final Jdbi jdbi;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public NamespaceResourceV3(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @GET
  public Response listNamespaces(@QueryParam("limit") Integer limit) {
    int l = limit == null ? 100 : limit;

    Map<String, Object> params = new HashMap<>();
    params.put("lim", l);

    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(params);
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    String sql =
        "SELECT agtype_to_json(n) FROM ag_catalog.cypher('marquez_graph', $$ "
            + "MATCH (n:Namespace) RETURN properties(n) LIMIT $lim "
            + "$$, ?) as (n agtype)";

    List<JsonNode> result =
        jdbi.withHandle(
            handle -> {
              try {
                List<JsonNode> rows = new ArrayList<>();
                Connection conn = handle.getConnection();
                if (conn != null) {
                  GraphDao.initAgeSession(conn);
                  try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setObject(1, GraphDao.createAgtype(paramsJson));
                    try (ResultSet rs = ps.executeQuery()) {
                      while (rs.next()) {
                        rows.add(MAPPER.readTree(rs.getString(1)));
                      }
                    }
                  }
                }
                return rows;
              } catch (Exception e) {
                throw new RuntimeException("Cypher query failed", e);
              }
            });

    return Response.ok(Map.of("namespaces", result)).build();
  }

  @GET
  @Path("{namespace}")
  public Response getNamespace(@PathParam("namespace") String namespace) {
    Map<String, Object> params = new HashMap<>();
    params.put("ns", namespace);

    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(params);
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    String sql =
        "SELECT agtype_to_json(n) FROM ag_catalog.cypher('marquez_graph', $$ "
            + "MATCH (n:Namespace {name: $ns}) RETURN properties(n) "
            + "$$, ?) as (n agtype)";

    List<JsonNode> result =
        jdbi.withHandle(
            handle -> {
              try {
                List<JsonNode> rows = new ArrayList<>();
                Connection conn = handle.getConnection();
                if (conn != null) {
                  GraphDao.initAgeSession(conn);
                  try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setObject(1, GraphDao.createAgtype(paramsJson));
                    try (ResultSet rs = ps.executeQuery()) {
                      while (rs.next()) {
                        rows.add(MAPPER.readTree(rs.getString(1)));
                      }
                    }
                  }
                }
                return rows;
              } catch (Exception e) {
                throw new RuntimeException("Cypher query failed", e);
              }
            });

    if (result.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    return Response.ok(result.get(0)).build();
  }
}

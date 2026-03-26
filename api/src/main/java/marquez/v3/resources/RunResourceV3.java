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

@Path("/api/v3/jobs/runs")
@Produces(MediaType.APPLICATION_JSON)
public class RunResourceV3 {
  private final Jdbi jdbi;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public RunResourceV3(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @GET
  public Response listRuns(@QueryParam("limit") Integer limit) {
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
        String.format(
            "SELECT %sagtype_to_json(r) FROM %scypher('marquez_graph', $$ "
                + "MATCH (r:Run) RETURN properties(r) LIMIT $lim "
                + "$$, ?) as (r %sagtype)",
            GraphDao.prefix(), GraphDao.prefix(), GraphDao.prefix());

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

    return Response.ok(Map.of("runs", result)).build();
  }

  @GET
  @Path("/{id}")
  public Response getRun(@PathParam("id") String id) {
    Map<String, Object> params = new HashMap<>();
    params.put("id", id);

    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(params);
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    String sql =
        String.format(
            "SELECT %sagtype_to_json(r) FROM %scypher('marquez_graph', $$ "
                + "MATCH (r:Run {runId: $id}) "
                + "RETURN properties(r) "
                + "$$, ?) as (r %sagtype)",
            GraphDao.prefix(), GraphDao.prefix(), GraphDao.prefix());

    return jdbi.withHandle(
        handle -> {
          try {
            Connection conn = handle.getConnection();
            GraphDao.initAgeSession(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
              ps.setObject(1, GraphDao.createAgtype(paramsJson));
              try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                  return Response.ok(MAPPER.readTree(rs.getString(1))).build();
                }
              }
            }
            return Response.status(Response.Status.NOT_FOUND).build();
          } catch (Exception e) {
            throw new RuntimeException("Cypher query failed", e);
          }
        });
  }

  @GET
  @Path("/{id}/inputs")
  public Response getInputs(@PathParam("id") String id) {
    Map<String, Object> params = new HashMap<>();
    params.put("id", id);

    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(params);
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    String sql =
        String.format(
            "SELECT %sagtype_to_json(dv) FROM %scypher('marquez_graph', $$ "
                + "MATCH (r:Run {runId: $id})-[:HAS_INPUT]->(dv:DatasetVersion) "
                + "RETURN properties(dv) "
                + "$$, ?) as (dv %sagtype)",
            GraphDao.prefix(), GraphDao.prefix(), GraphDao.prefix());

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

    return Response.ok(Map.of("inputs", result)).build();
  }

  @GET
  @Path("/{id}/outputs")
  public Response getOutputs(@PathParam("id") String id) {
    Map<String, Object> params = new HashMap<>();
    params.put("id", id);

    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(params);
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    String sql =
        String.format(
            "SELECT %sagtype_to_json(dv) FROM %scypher('marquez_graph', $$ "
                + "MATCH (r:Run {runId: $id})-[:HAS_OUTPUT]->(dv:DatasetVersion) "
                + "RETURN properties(dv) "
                + "$$, ?) as (dv %sagtype)",
            GraphDao.prefix(), GraphDao.prefix(), GraphDao.prefix());

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

    return Response.ok(Map.of("outputs", result)).build();
  }
}

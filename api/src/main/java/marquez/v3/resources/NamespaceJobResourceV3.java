/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

@Path("/api/v3/namespaces/{namespace}/jobs")
@Produces(MediaType.APPLICATION_JSON)
public class NamespaceJobResourceV3 {

  private final Jdbi jdbi;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public NamespaceJobResourceV3(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @GET
  public Response listJobs(
      @PathParam("namespace") String namespace,
      @QueryParam("limit") Integer limit,
      @QueryParam("offset") Integer offset) {
    int l = limit == null ? 100 : limit;
    int o = offset == null ? 0 : offset;

    Map<String, Object> params = new HashMap<>();
    params.put("ns", namespace);
    params.put("lim", l);
    params.put("off", o);

    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(params);
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    String sql =
        String.format(
            "SELECT %sagtype_to_json(n) FROM %scypher('marquez_graph', $$ "
                + "MATCH (:Namespace {name: $ns})-[:HAS_JOB]->(j) "
                + "RETURN properties(j) "
                + "SKIP $off LIMIT $lim "
                + "$$, ?) as (n %sagtype)",
            GraphDao.prefix(), GraphDao.prefix(), GraphDao.prefix());

    List<ObjectNode> jobs = executeQueryInternal(sql, paramsJson);
    return Response.ok(Map.of("jobs", jobs, "totalCount", jobs.size())).build();
  }

  @GET
  @Path("/{job}")
  public Response getJob(@PathParam("namespace") String namespace, @PathParam("job") String job) {
    Map<String, Object> params = new HashMap<>();
    params.put("ns", namespace);
    params.put("job", job);

    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(params);
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    String sql =
        String.format(
            "SELECT %sagtype_to_json(n) FROM %scypher('marquez_graph', $$ "
                + "MATCH (:Namespace {name: $ns})-[:HAS_JOB]->(j:Job {name: $job}) "
                + "RETURN properties(j) "
                + "$$, ?) as (n %sagtype)",
            GraphDao.prefix(), GraphDao.prefix(), GraphDao.prefix());

    List<ObjectNode> jobs = executeQueryInternal(sql, paramsJson);
    if (jobs.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    return Response.ok(jobs.get(0)).build();
  }

  @GET
  @Path("/{job}/runs")
  public Response getJobRuns(
      @PathParam("namespace") String namespace,
      @PathParam("job") String job,
      @QueryParam("limit") Integer limit,
      @QueryParam("offset") Integer offset) {
    int l = limit == null ? 10 : limit;
    int o = offset == null ? 0 : offset;

    Map<String, Object> params = new HashMap<>();
    params.put("ns", namespace);
    params.put("job", job);
    params.put("lim", l);
    params.put("off", o);

    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(params);
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    String sql =
        String.format(
            "SELECT %sagtype_to_json(n) FROM %scypher('marquez_graph', $$ "
                + "MATCH (:Namespace {name: $ns})-[:HAS_JOB]->(:Job {name: $job})-[:HAS_VERSION]->()-[:HAS_RUN]->(r) "
                + "RETURN properties(r) "
                + "ORDER BY r.createdAt DESC "
                + "SKIP $off LIMIT $lim "
                + "$$, ?) as (n %sagtype)",
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
                      ObjectNode runProps = (ObjectNode) MAPPER.readTree(rs.getString(1));

                      // Compatibility fields for UI
                      String runId =
                          runProps.has("runId") ? runProps.get("runId").asText() : "unknown";
                      runProps.put("id", runId);
                      if (!runProps.has("createdAt")) {
                        runProps.put("createdAt", "2024-01-01T00:00:00Z");
                      }
                      if (!runProps.has("updatedAt")) {
                        runProps.put("updatedAt", "2024-01-01T00:00:00Z");
                      }
                      if (!runProps.has("startedAt")) {
                        runProps.put("startedAt", "2024-01-01T00:00:00Z");
                      }
                      if (!runProps.has("endedAt")) {
                        runProps.put("endedAt", "2024-01-01T00:00:00Z");
                      }
                      if (!runProps.has("durationMs")) {
                        runProps.put("durationMs", 0);
                      }
                      if (!runProps.has("state")) {
                        runProps.put("state", "COMPLETED");
                      }

                      // Handle JSON fields
                      if (runProps.has("facets") && runProps.get("facets").isTextual()) {
                        try {
                          runProps.set("facets", MAPPER.readTree(runProps.get("facets").asText()));
                        } catch (Exception e) {
                        }
                      }

                      rows.add(runProps);
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

  private List<ObjectNode> executeQueryInternal(String sql, String paramsJson) {
    return jdbi.withHandle(
        handle -> {
          try {
            List<ObjectNode> rows = new ArrayList<>();
            Connection conn = handle.getConnection();
            GraphDao.initAgeSession(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
              ps.setObject(1, GraphDao.createAgtype(paramsJson));
              try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                  ObjectNode props = (ObjectNode) MAPPER.readTree(rs.getString(1));

                  // Handle JSON fields that might be stored as strings
                  if (props.has("facets") && props.get("facets").isTextual()) {
                    try {
                      props.set("facets", MAPPER.readTree(props.get("facets").asText()));
                    } catch (Exception e) {
                    }
                  }

                  ObjectNode job = MAPPER.createObjectNode();
                  job.setAll(props);
                  job.put(
                      "namespace",
                      props.has("namespace") ? props.get("namespace").asText() : "default");
                  job.put("name", props.has("name") ? props.get("name").asText() : "");
                  job.put("type", props.has("type") ? props.get("type").asText() : "BATCH");
                  job.put(
                      "createdAt",
                      props.has("createdAt")
                          ? props.get("createdAt").asText()
                          : "2024-01-01T00:00:00Z");
                  job.put(
                      "updatedAt",
                      props.has("updatedAt")
                          ? props.get("updatedAt").asText()
                          : "2024-01-01T00:00:00Z");

                  if (!job.has("tags")) {
                    job.set("tags", MAPPER.createArrayNode());
                  }
                  if (!job.has("description")) {
                    job.put("description", "");
                  }
                  if (!job.has("latestRuns")) {
                    job.set("latestRuns", MAPPER.createArrayNode());
                  }

                  ObjectNode id = MAPPER.createObjectNode();
                  id.put("namespace", job.get("namespace").asText());
                  id.put("name", job.get("name").asText());
                  job.set("id", id);

                  rows.add(job);
                }
              }
            }
            return rows;
          } catch (Exception e) {
            throw new RuntimeException("Cypher query failed", e);
          }
        });
  }
}

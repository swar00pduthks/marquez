/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import marquez.v3.db.GraphDao;
import org.jdbi.v3.core.Jdbi;

@Path("/api/v3/jobs")
@Produces(MediaType.APPLICATION_JSON)
public class JobResourceV3 {

  private final Jdbi jdbi;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public JobResourceV3(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @GET
  public Response listGlobalJobs(
      @QueryParam("limit") Integer limit, @QueryParam("offset") Integer offset) {
    int l = limit == null ? 100 : limit;
    int o = offset == null ? 0 : offset;

    Map<String, Object> params = new HashMap<>();
    params.put("lim", l);
    params.put("off", o);

    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(params);
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    String sql =
        "SELECT agtype_to_json(n) FROM ag_catalog.cypher('marquez_graph', $$ "
            + "MATCH (j:Job) "
            + "RETURN properties(j) "
            + "SKIP $off LIMIT $lim "
            + "$$, ?) as (n agtype)";

    return executeQuery(sql, paramsJson);
  }

  private Response executeQuery(String sql, String paramsJson) {
    List<ObjectNode> result =
        jdbi.withHandle(
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

    return Response.ok(Map.of("jobs", result, "totalCount", result.size())).build();
  }
}

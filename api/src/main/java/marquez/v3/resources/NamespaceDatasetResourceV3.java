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

@Path("/api/v3/namespaces/{namespace}/datasets")
@Produces(MediaType.APPLICATION_JSON)
public class NamespaceDatasetResourceV3 {

  private final Jdbi jdbi;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public NamespaceDatasetResourceV3(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @GET
  public Response listDatasets(
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
        "SELECT agtype_to_json(n) FROM ag_catalog.cypher('marquez_graph', $$ "
            + "MATCH (:Namespace {name: $ns})-[:HAS_DATASET]->(d) "
            + "RETURN properties(d) "
            + "SKIP $off LIMIT $lim "
            + "$$, ?) as (n agtype)";

    List<ObjectNode> datasets = executeQueryInternal(sql, paramsJson);
    return Response.ok(Map.of("datasets", datasets, "totalCount", datasets.size())).build();
  }

  @GET
  @Path("/{dataset}")
  public Response getDataset(
      @PathParam("namespace") String namespace, @PathParam("dataset") String dataset) {
    Map<String, Object> params = new HashMap<>();
    params.put("ns", namespace);
    params.put("ds", dataset);

    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(params);
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    String sql =
        "SELECT agtype_to_json(n) FROM ag_catalog.cypher('marquez_graph', $$ "
            + "MATCH (:Namespace {name: $ns})-[:HAS_DATASET]->(d:Dataset {name: $ds}) "
            + "RETURN properties(d) "
            + "$$, ?) as (n agtype)";

    List<ObjectNode> datasets = executeQueryInternal(sql, paramsJson);
    if (datasets.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    return Response.ok(datasets.get(0)).build();
  }

  @GET
  @Path("/{dataset}/versions")
  public Response getDatasetVersions(
      @PathParam("namespace") String namespace,
      @PathParam("dataset") String dataset,
      @QueryParam("limit") Integer limit,
      @QueryParam("offset") Integer offset) {
    int l = limit == null ? 10 : limit;
    int o = offset == null ? 0 : offset;

    Map<String, Object> params = new HashMap<>();
    params.put("ns", namespace);
    params.put("ds", dataset);
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
            + "MATCH (:Namespace {name: $ns})-[:HAS_DATASET]->(:Dataset {name: $ds})-[:HAS_VERSION]->(v) "
            + "RETURN properties(v) "
            + "SKIP $off LIMIT $lim "
            + "$$, ?) as (n agtype)";

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
                      ObjectNode props = (ObjectNode) MAPPER.readTree(rs.getString(1));
                      // Handle JSON fields
                      if (props.has("facets") && props.get("facets").isTextual()) {
                        try {
                          props.set("facets", MAPPER.readTree(props.get("facets").asText()));
                        } catch (Exception e) {
                        }
                      }
                      rows.add(props);
                    }
                  }
                }
                return rows;
              } catch (Exception e) {
                throw new RuntimeException("Cypher query failed", e);
              }
            });

    return Response.ok(Map.of("versions", result)).build();
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

                  ObjectNode dataset = MAPPER.createObjectNode();
                  dataset.setAll(props);
                  dataset.put(
                      "namespace",
                      props.has("namespace") ? props.get("namespace").asText() : "default");
                  dataset.put("name", props.has("name") ? props.get("name").asText() : "");
                  dataset.put(
                      "createdAt",
                      props.has("createdAt")
                          ? props.get("createdAt").asText()
                          : "2024-01-01T00:00:00Z");
                  dataset.put(
                      "updatedAt",
                      props.has("updatedAt")
                          ? props.get("updatedAt").asText()
                          : "2024-01-01T00:00:00Z");

                  if (!dataset.has("tags")) {
                    dataset.set("tags", MAPPER.createArrayNode());
                  }
                  if (!dataset.has("fields")) {
                    dataset.set("fields", MAPPER.createArrayNode());
                  }
                  if (!dataset.has("description")) {
                    dataset.put("description", "");
                  }

                  ObjectNode id = MAPPER.createObjectNode();
                  id.put("namespace", dataset.get("namespace").asText());
                  id.put("name", dataset.get("name").asText());
                  dataset.set("id", id);

                  rows.add(dataset);
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

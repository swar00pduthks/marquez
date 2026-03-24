/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import marquez.service.OpenLineageService;
import marquez.service.models.LineageEvent;
import marquez.v3.db.GraphDao;
import org.jdbi.v3.core.Jdbi;

@Slf4j
@Path("/api/v3/lineage")
@Produces(MediaType.APPLICATION_JSON)
public class OpenLineageResourceV3 {
  private final Jdbi jdbi;
  private final GraphDao graphDao;
  private final OpenLineageService openLineageService;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String GRAPH_NAME = "marquez_graph";

  public OpenLineageResourceV3(
      Jdbi jdbi, GraphDao graphDao, OpenLineageService openLineageService) {
    this.jdbi = jdbi;
    this.graphDao = graphDao;
    this.openLineageService = openLineageService;
  }

  @GET
  public Response getLineageGraph(@Context UriInfo uriInfo) {
    MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
    String nodeId = queryParams.getFirst("nodeId"); // job:namespace:name or dataset:namespace:name
    String depth = queryParams.getFirst("depth");
    int d = depth == null ? 1 : Integer.parseInt(depth);

    if (nodeId == null) {
      return Response.status(Response.Status.BAD_REQUEST).entity("Missing nodeId").build();
    }

    String fqn;
    try {
      fqn = nodeId.substring(nodeId.indexOf(":") + 1);
    } catch (Exception e) {
      return Response.status(Response.Status.BAD_REQUEST).entity("Invalid nodeId").build();
    }

    Map<String, Object> params = new HashMap<>();
    params.put("fqn", fqn);

    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(params);
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    // Cypher query to get paths. We then extract nodes and edges.
    String sql =
        "SELECT agtype_to_json(path) "
            + "FROM ag_catalog.cypher('marquez_graph', $$ "
            + "MATCH path = (n {fqn: $fqn})-[*1.."
            + d
            + "]-(m) "
            + "RETURN path "
            + "$$, ?) as (path agtype)";

    return jdbi.withHandle(
        handle -> {
          try {
            Map<String, ObjectNode> nodesMap = new HashMap<>();
            Connection conn = handle.getConnection();
            if (conn != null) {
              GraphDao.initAgeSession(conn);

              try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, GraphDao.createAgtype(paramsJson));
                try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                    JsonNode path = MAPPER.readTree(rs.getString(1));

                    Map<String, String> internalToUiId = new HashMap<>();

                    // First pass: identify and store all Job/Dataset nodes
                    for (int i = 0; i < path.size(); i += 1) {
                      JsonNode item = path.get(i);
                      if (item.has("label")) { // It's a node
                        ObjectNode node = (ObjectNode) item;
                        ObjectNode props = (ObjectNode) node.get("properties");

                        // Handle JSON fields that might be stored as strings
                        if (props.has("facets") && props.get("facets").isTextual()) {
                          try {
                            props.set("facets", MAPPER.readTree(props.get("facets").asText()));
                          } catch (Exception e) {
                          }
                        }

                        String label = node.get("label").asText();
                        if (label.equals("Job") || label.equals("Dataset")) {
                          String uiId = getUiId(node);
                          String internalId = node.get("id").asText();
                          internalToUiId.put(internalId, uiId);
                          if (!nodesMap.containsKey(uiId)) {
                            nodesMap.put(uiId, createUiNode(node));
                          }
                        }
                      }
                    }

                    // Second pass: synthesize Job-Dataset edges
                    for (int i = 0; i < path.size(); i += 1) {
                      JsonNode item = path.get(i);
                      if (item.has("start_id")) { // It's an edge
                        String startInternalId = item.get("start_id").asText();
                        String endInternalId = item.get("end_id").asText();
                        String label = item.get("label").asText();

                        String startUiId = internalToUiId.get(startInternalId);
                        String endUiId = internalToUiId.get(endInternalId);

                        if (startUiId != null && endUiId != null) {
                          addEdge(nodesMap.get(startUiId), startUiId, endUiId, "out");
                          addEdge(nodesMap.get(endUiId), startUiId, endUiId, "in");
                        }
                      }
                    }
                  }
                }
              }

              // If the starting node was not involved in any path (no edges out of it), add it
              // manually
              if (nodesMap.isEmpty()) {
                String findSql =
                    "SELECT agtype_to_json(n) FROM ag_catalog.cypher('marquez_graph', $$ MATCH (n {fqn: $fqn}) RETURN n $$, ?) as (n agtype)";
                try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                  ps.setObject(1, GraphDao.createAgtype(paramsJson));
                  try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                      JsonNode node = MAPPER.readTree(rs.getString(1));
                      String uiId = getUiId(node);
                      nodesMap.put(uiId, createUiNode(node));
                    }
                  }
                }
              }

              // Third pass: Fetch fields for all datasets
              if (conn != null) {
                for (ObjectNode uiNode : nodesMap.values()) {
                  if (uiNode.get("type").asText().equals("DATASET")) {
                    String dsFqn = uiNode.get("data").get("fqn").asText();
                    ArrayNode fields = (ArrayNode) uiNode.get("data").get("fields");

                    Map<String, Object> fieldParams = new HashMap<>();
                    fieldParams.put("fqn", dsFqn);
                    String fieldParamsJson = MAPPER.writeValueAsString(fieldParams);

                    String fieldSql =
                        "SELECT agtype_to_json(properties(f)) "
                            + "FROM ag_catalog.cypher('marquez_graph', $$ "
                            + "MATCH (d:Dataset {fqn: $fqn})-[:HAS_VERSION]->(dv:DatasetVersion)-[:HAS_FIELD]->(f:DatasetField) "
                            + "RETURN f "
                            + "$$, ?) as (f agtype)";

                    try (PreparedStatement ps = conn.prepareStatement(fieldSql)) {
                      ps.setObject(1, GraphDao.createAgtype(fieldParamsJson));
                      try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                          fields.add(MAPPER.readTree(rs.getString(1)));
                        }
                      }
                    }
                  }
                }
              }
            }

            return Response.ok(Map.of("graph", nodesMap.values())).build();
          } catch (Exception e) {
            log.error("Failed to fetch lineage graph", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
          }
        });
  }

  private ObjectNode createUiNode(JsonNode node) {
    JsonNode props = node.get("properties");
    String label = node.get("label").asText();
    String uiId = getUiId(node);

    ObjectNode data = MAPPER.createObjectNode();
    data.setAll((ObjectNode) props);
    // Compatibility fields for UI
    data.put(
        "fqn",
        props.has("fqn")
            ? props.get("fqn").asText()
            : (props.has("name") ? props.get("name").asText() : ""));
    data.put("name", props.has("name") ? props.get("name").asText() : "");
    data.put("namespace", props.has("namespace") ? props.get("namespace").asText() : "default");

    ObjectNode id = MAPPER.createObjectNode();
    id.put("namespace", data.get("namespace").asText());
    id.put("name", data.get("name").asText());
    data.set("id", id);

    data.set("inputs", MAPPER.createArrayNode());
    data.set("outputs", MAPPER.createArrayNode());
    if (label.equals("Dataset")) {
      data.set("fields", MAPPER.createArrayNode());
    }
    if (!data.has("tags")) {
      data.set("tags", MAPPER.createArrayNode());
    }
    if (!data.has("description")) {
      data.put("description", "");
    }
    data.set("latestRun", null);

    ObjectNode uiNode = MAPPER.createObjectNode();
    uiNode.put("id", uiId);
    uiNode.put("type", label.toUpperCase());
    uiNode.set("data", data);
    uiNode.set("inEdges", MAPPER.createArrayNode());
    uiNode.set("outEdges", MAPPER.createArrayNode());
    return uiNode;
  }

  private void addEdge(ObjectNode node, String startUiId, String endUiId, String direction) {
    ArrayNode edges = (ArrayNode) node.get(direction + "Edges");
    boolean exists = false;
    for (JsonNode e : edges) {
      if (e.get("origin").asText().equals(startUiId)
          && e.get("destination").asText().equals(endUiId)) {
        exists = true;
        break;
      }
    }
    if (!exists) {
      ObjectNode uiEdge = MAPPER.createObjectNode();
      uiEdge.put("origin", startUiId);
      uiEdge.put("destination", endUiId);
      edges.add(uiEdge);
    }
  }

  private String getUiId(JsonNode node) {
    if (node == null || node.isMissingNode()) return "";
    String label = node.get("label").asText();
    JsonNode props = node.get("properties");
    String fqn =
        props.has("fqn")
            ? props.get("fqn").asText()
            : (props.has("name") ? props.get("name").asText() : "");
    return label.toLowerCase() + ":" + fqn;
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response createLineage(LineageEvent event) {
    if (event == null || event.getJob() == null || event.getRun() == null) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity("Missing Job or Run in Lineage Event")
          .build();
    }

    jdbi.useTransaction(
        handle -> {
          try {
            Connection conn = handle.getConnection();
            if (conn != null) {
              GraphDao.initAgeSession(conn);

              String sourceName = "default";
              if (event.getJob().getNamespace() != null) {
                Map<String, Object> srcProps = new HashMap<>();
                srcProps.put("name", sourceName);
                srcProps.put("type", "unknown");
                graphDao.upsertNode(handle, GRAPH_NAME, "Source", "name", srcProps);

                Map<String, Object> nsProps = new HashMap<>();
                nsProps.put("name", event.getJob().getNamespace());
                graphDao.upsertNode(handle, GRAPH_NAME, "Namespace", "name", nsProps);
                graphDao.upsertEdge(
                    handle,
                    GRAPH_NAME,
                    "HAS_NAMESPACE",
                    "Source",
                    "name",
                    sourceName,
                    "Namespace",
                    "name",
                    event.getJob().getNamespace());
              }

              String jobFqn = event.getJob().getNamespace() + ":" + event.getJob().getName();
              Map<String, Object> jobProps = new HashMap<>();
              jobProps.put("name", event.getJob().getName());
              jobProps.put("namespace", event.getJob().getNamespace());
              jobProps.put("fqn", jobFqn);
              if (event.getJob().getFacets() != null) {
                jobProps.put("facets", MAPPER.valueToTree(event.getJob().getFacets()));
              }
              graphDao.upsertNode(handle, GRAPH_NAME, "Job", "fqn", jobProps);
              graphDao.upsertEdge(
                  handle,
                  GRAPH_NAME,
                  "HAS_JOB",
                  "Namespace",
                  "name",
                  event.getJob().getNamespace(),
                  "Job",
                  "fqn",
                  jobFqn);

              String jobContextJson = safeJson(event.getJob().getFacets());
              String jobInputs = safeJson(event.getInputs());
              String jobOutputs = safeJson(event.getOutputs());
              String jobVersionSignature = jobFqn + jobContextJson + jobInputs + jobOutputs;
              String jobVersionUuid = generateDeterministicUuid(jobVersionSignature);

              Map<String, Object> jvProps = new HashMap<>();
              jvProps.put("uuid", jobVersionUuid);
              jvProps.put("version", jobVersionUuid);
              jvProps.put("jobContext", jobContextJson);
              graphDao.upsertNode(handle, GRAPH_NAME, "JobVersion", "uuid", jvProps);
              graphDao.upsertEdge(
                  handle,
                  GRAPH_NAME,
                  "HAS_VERSION",
                  "Job",
                  "fqn",
                  jobFqn,
                  "JobVersion",
                  "uuid",
                  jobVersionUuid);

              String runId = event.getRun().getRunId();
              String eventType = event.getEventType() != null ? event.getEventType() : "START";

              Map<String, Object> runProps = new HashMap<>();
              runProps.put("runId", runId);
              runProps.put("fqn", jobFqn);
              runProps.put("createdAt", event.getEventTime());
              runProps.put("updatedAt", event.getEventTime());
              runProps.put("startedAt", event.getEventTime());
              runProps.put("endedAt", event.getEventTime());
              runProps.put("durationMs", 0);
              runProps.put("state", eventType);
              if (event.getRun().getFacets() != null) {
                runProps.put("facets", MAPPER.valueToTree(event.getRun().getFacets()));
              }
              graphDao.upsertNode(handle, GRAPH_NAME, "Run", "runId", runProps);
              graphDao.upsertEdge(
                  handle,
                  GRAPH_NAME,
                  "HAS_RUN",
                  "JobVersion",
                  "uuid",
                  jobVersionUuid,
                  "Run",
                  "runId",
                  runId);

              if (event.getRun().getFacets() != null
                  && event.getRun().getFacets().getParent() != null) {
                String parentRunId = event.getRun().getFacets().getParent().getRun().getRunId();
                if (parentRunId != null) {
                  Map<String, Object> pRunProps = new HashMap<>();
                  pRunProps.put("runId", parentRunId);
                  graphDao.upsertNode(handle, GRAPH_NAME, "Run", "runId", pRunProps);
                  graphDao.upsertEdge(
                      handle,
                      GRAPH_NAME,
                      "PARENT_RUN",
                      "Run",
                      "runId",
                      runId,
                      "Run",
                      "runId",
                      parentRunId);
                }
              }

              String runStateUuid = generateDeterministicUuid(runId + eventType);
              Map<String, Object> rsProps = new HashMap<>();
              rsProps.put("uuid", runStateUuid);
              rsProps.put("state", eventType);
              graphDao.upsertNode(handle, GRAPH_NAME, "RunState", "uuid", rsProps);
              graphDao.upsertEdge(
                  handle,
                  GRAPH_NAME,
                  "HAS_STATE",
                  "Run",
                  "runId",
                  runId,
                  "RunState",
                  "uuid",
                  runStateUuid);

              if (event.getInputs() != null) {
                for (LineageEvent.Dataset ds : event.getInputs()) {
                  String dsFqn = ds.getNamespace() + ":" + ds.getName();
                  Map<String, Object> dsProps = new HashMap<>();
                  dsProps.put("fqn", dsFqn);
                  dsProps.put("name", ds.getName());
                  dsProps.put("namespace", ds.getNamespace());
                  if (ds.getFacets() != null) {
                    dsProps.put("facets", MAPPER.valueToTree(ds.getFacets()));
                  }
                  graphDao.upsertNode(handle, GRAPH_NAME, "Dataset", "fqn", dsProps);
                  graphDao.upsertEdge(
                      handle,
                      GRAPH_NAME,
                      "HAS_DATASET",
                      "Namespace",
                      "name",
                      ds.getNamespace(),
                      "Dataset",
                      "fqn",
                      dsFqn);

                  String dsSchema = safeJson(ds.getFacets());
                  String dvUuid = generateDeterministicUuid(dsFqn + dsSchema);
                  Map<String, Object> dvProps = new HashMap<>();
                  dvProps.put("uuid", dvUuid);
                  dvProps.put("datasetFqn", dsFqn);
                  graphDao.upsertNode(handle, GRAPH_NAME, "DatasetVersion", "uuid", dvProps);
                  graphDao.upsertEdge(
                      handle,
                      GRAPH_NAME,
                      "HAS_VERSION",
                      "Dataset",
                      "fqn",
                      dsFqn,
                      "DatasetVersion",
                      "uuid",
                      dvUuid);
                  graphDao.upsertEdge(
                      handle,
                      GRAPH_NAME,
                      "HAS_INPUT",
                      "Run",
                      "runId",
                      runId,
                      "DatasetVersion",
                      "uuid",
                      dvUuid);

                  // Direct edge for easier lineage traversal
                  graphDao.upsertEdge(
                      handle,
                      GRAPH_NAME,
                      "INPUT_TO",
                      "Dataset",
                      "fqn",
                      dsFqn,
                      "Job",
                      "fqn",
                      jobFqn);
                }
              }

              if (event.getOutputs() != null) {
                for (LineageEvent.Dataset ds : event.getOutputs()) {
                  String dsFqn = ds.getNamespace() + ":" + ds.getName();
                  Map<String, Object> dsProps = new HashMap<>();
                  dsProps.put("fqn", dsFqn);
                  dsProps.put("name", ds.getName());
                  dsProps.put("namespace", ds.getNamespace());
                  if (ds.getFacets() != null) {
                    dsProps.put("facets", MAPPER.valueToTree(ds.getFacets()));
                  }
                  graphDao.upsertNode(handle, GRAPH_NAME, "Dataset", "fqn", dsProps);
                  graphDao.upsertEdge(
                      handle,
                      GRAPH_NAME,
                      "HAS_DATASET",
                      "Namespace",
                      "name",
                      ds.getNamespace(),
                      "Dataset",
                      "fqn",
                      dsFqn);

                  String dsSchema = safeJson(ds.getFacets());
                  String dvUuid = generateDeterministicUuid(dsFqn + dsSchema);
                  Map<String, Object> dvProps = new HashMap<>();
                  dvProps.put("uuid", dvUuid);
                  dvProps.put("datasetFqn", dsFqn);
                  graphDao.upsertNode(handle, GRAPH_NAME, "DatasetVersion", "uuid", dvProps);
                  graphDao.upsertEdge(
                      handle,
                      GRAPH_NAME,
                      "HAS_VERSION",
                      "Dataset",
                      "fqn",
                      dsFqn,
                      "DatasetVersion",
                      "uuid",
                      dvUuid);
                  graphDao.upsertEdge(
                      handle,
                      GRAPH_NAME,
                      "HAS_OUTPUT",
                      "Run",
                      "runId",
                      runId,
                      "DatasetVersion",
                      "uuid",
                      dvUuid);

                  // Direct edge for easier lineage traversal
                  graphDao.upsertEdge(
                      handle,
                      GRAPH_NAME,
                      "OUTPUT_FROM",
                      "Job",
                      "fqn",
                      jobFqn,
                      "Dataset",
                      "fqn",
                      dsFqn);

                  if (ds.getFacets() != null
                      && ds.getFacets().getSchema() != null
                      && ds.getFacets().getSchema().getFields() != null) {
                    for (LineageEvent.SchemaField field : ds.getFacets().getSchema().getFields()) {
                      String fieldId = dvUuid + ":" + field.getName();
                      Map<String, Object> fieldProps = new HashMap<>();
                      fieldProps.put("id", fieldId);
                      fieldProps.put("name", field.getName());
                      fieldProps.put("type", field.getType());
                      graphDao.upsertNode(handle, GRAPH_NAME, "DatasetField", "id", fieldProps);
                      graphDao.upsertEdge(
                          handle,
                          GRAPH_NAME,
                          "HAS_FIELD",
                          "DatasetVersion",
                          "uuid",
                          dvUuid,
                          "DatasetField",
                          "id",
                          fieldId);
                    }
                  }
                }
              }
            }
          } catch (Exception e) {
            throw new RuntimeException("Failed to ingest lineage event", e);
          }
        });

    try {
      openLineageService.createAsync(event);
    } catch (Exception e) {
      log.warn("Failed to ingest event into relational store", e);
    }

    return Response.status(Response.Status.CREATED).build();
  }

  private String safeJson(Object obj) {
    if (obj == null) return "{}";
    try {
      return MAPPER.writeValueAsString(obj);
    } catch (Exception e) {
      return "{}";
    }
  }

  private String generateDeterministicUuid(String input) {
    return java.util.UUID.nameUUIDFromBytes(input.getBytes()).toString();
  }
}

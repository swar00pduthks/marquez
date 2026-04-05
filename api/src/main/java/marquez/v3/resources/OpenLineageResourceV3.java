/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import marquez.service.OpenLineageService;
import marquez.service.models.LineageEvent;
import marquez.v3.db.GraphDao;
import marquez.v3.db.GraphWriter;
import org.jdbi.v3.core.Jdbi;

/**
 * V3 lineage REST resource backed by the Apache AGE property graph.
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>{@code POST /api/v3/lineage} – ingests an OpenLineage event into both the relational store
 *       (via {@link OpenLineageService}) and the AGE graph (via {@link GraphWriter}).
 *   <li>{@code GET /api/v3/lineage?nodeId=&depth=&aggregateToParentRun=} – returns the lineage
 *       graph for the given node. Supports the same {@code aggregateToParentRun} query parameter as
 *       {@code /api/v1/lineage} and {@code /api/v2/lineage} for V1/V2 parity.
 * </ul>
 *
 * <h2>GET query semantics</h2>
 *
 * <p>Only {@code INPUT_TO} and {@code PRODUCES} edges are traversed (the job↔dataset I/O
 * shortcuts). Internal structural edges ({@code HAS_RUN}, {@code HAS_JOB_VERSION}, etc.) are
 * intentionally excluded so that depth behaves identically to V1/V2: depth=1 returns the start node
 * and its direct I/O datasets; depth=2 adds the jobs that share those datasets.
 *
 * <p>When {@code aggregateToParentRun=true} and the {@code nodeId} is of type {@code run:}, the
 * query traverses {@code HAS_CHILD_RUN*} edges from the parent run, unions all child run
 * inputs/outputs, and returns an aggregated run node whose state is determined by the worst-case
 * child state (FAIL > RUNNING > COMPLETE).
 */
@Slf4j
@Path("/api/v3/lineage")
@Produces(MediaType.APPLICATION_JSON)
public class OpenLineageResourceV3 {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String DEFAULT_DEPTH = "2";

  private final Jdbi jdbi;
  private final GraphWriter graphWriter;
  private final OpenLineageService openLineageService;

  public OpenLineageResourceV3(
      Jdbi jdbi, GraphWriter graphWriter, OpenLineageService openLineageService) {
    this.jdbi = jdbi;
    this.graphWriter = graphWriter;
    this.openLineageService = openLineageService;
  }

  // ---------------------------------------------------------------------------
  // POST – ingest
  // ---------------------------------------------------------------------------

  /**
   * Ingests an OpenLineage event.
   *
   * <p>Writes to the relational store (via {@link OpenLineageService#createAsync(LineageEvent)})
   * and synchronously to the AGE graph. The graph write is performed synchronously so that
   * subsequent GET /api/v3/lineage calls always see fresh data.
   */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response createLineage(LineageEvent event) {
    if (event == null || event.getJob() == null || event.getRun() == null) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity("{\"error\":\"Missing required fields: job and run\"}")
          .build();
    }
    // Relational write (async – does not block the response)
    try {
      openLineageService.createAsync(event);
    } catch (Exception e) {
      log.warn("Failed to enqueue lineage event for relational store", e);
    }
    // Graph write is synchronous so callers can query immediately after 201.
    // useHandle (autocommit) rather than useTransaction avoids the PostgreSQL
    // "current transaction is aborted" cascade on Azure when LOAD 'age' is blocked.
    // Cypher MERGE operations are idempotent so transaction atomicity is not required.
    try {
      jdbi.useHandle(
          handle -> {
            GraphDao.initAgeSession(handle.getConnection());
            graphWriter.writeEvent(handle, event);
          });
    } catch (Exception e) {
      log.error(
          "Graph write failed for run '{}': {}", event.getRun().getRunId(), e.getMessage(), e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Graph write failed: " + e.getMessage() + "\"}")
          .build();
    }
    return Response.status(Response.Status.CREATED).build();
  }

  // ---------------------------------------------------------------------------
  // GET – lineage graph query
  // ---------------------------------------------------------------------------

  /**
   * Returns the lineage graph for the given node.
   *
   * @param nodeId node identifier in the format {@code type:namespace:name} for jobs/datasets, or
   *     {@code run:runId} for runs
   * @param depth number of hops along I/O edges (default 2)
   * @param aggregateToParentRun when {@code true} and {@code nodeId} is a run, traverse {@code
   *     HAS_CHILD_RUN*} from the parent and aggregate child run states/inputs/outputs
   */
  @GET
  public Response getLineage(
      @QueryParam("nodeId") String nodeId,
      @QueryParam("depth") @DefaultValue(DEFAULT_DEPTH) int depth,
      @QueryParam("aggregateToParentRun") @DefaultValue("false") boolean aggregateToParentRun) {

    if (nodeId == null || nodeId.isBlank()) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity("{\"error\":\"Missing required query parameter: nodeId\"}")
          .build();
    }

    if (!GraphDao.isAgeAvailable()) {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .entity("{\"error\":\"Graph database (Apache AGE) is not available\"}")
          .build();
    }

    return jdbi.withHandle(
        handle -> {
          try {
            Connection conn = handle.getConnection();
            GraphDao.initAgeSession(conn);

            // nodeId format:  job:namespace:name
            //                 dataset:namespace:name
            //                 run:runId
            String type = nodeId.contains(":") ? nodeId.substring(0, nodeId.indexOf(':')) : "job";
            String value =
                nodeId.contains(":") ? nodeId.substring(nodeId.indexOf(':') + 1) : nodeId;

            if ("run".equalsIgnoreCase(type)) {
              return getRunLineage(conn, value, aggregateToParentRun);
            } else {
              String label = "dataset".equalsIgnoreCase(type) ? "Dataset" : "Job";
              return getJobDatasetLineage(conn, label, value, depth);
            }
          } catch (Exception e) {
            log.error("Lineage query failed for nodeId '{}'", nodeId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\":\"" + e.getMessage() + "\"}")
                .build();
          }
        });
  }

  // ---------------------------------------------------------------------------
  // Job / Dataset lineage (I/O edge traversal)
  // ---------------------------------------------------------------------------

  /**
   * Queries lineage for a Job or Dataset node by traversing only {@code INPUT_TO} and {@code
   * PRODUCES} edges. This matches V1/V2 depth semantics exactly.
   *
   * <p>Uses two separate Cypher queries (nodes then edges) rather than a PATH return to avoid the
   * {@code agtype_to_json(PATH)} issue — vertex and edge agtypes are handled correctly by {@code
   * agtype_to_json}, while the PATH composite type is not.
   */
  private Response getJobDatasetLineage(Connection conn, String startLabel, String fqn, int depth)
      throws Exception {

    String paramsJson = MAPPER.writeValueAsString(Map.of("fqn", fqn));
    Map<String, ObjectNode> nodesMap = new LinkedHashMap<>();
    Map<Long, String> idToUiId = new HashMap<>();

    // Step 1: Always fetch the start node first so idToUiId is populated.
    {
      String startSql =
          String.format(
              "SELECT agtype_to_json(n) "
                  + "FROM %scypher('marquez_graph', $$ "
                  + "MATCH (n:%s {fqn: $fqn}) RETURN n LIMIT 1 "
                  + "$$, ?) as (n %sagtype)",
              GraphDao.prefix(), startLabel, GraphDao.prefix());
      try (PreparedStatement ps = conn.prepareStatement(startSql)) {
        ps.setObject(1, GraphDao.createAgtype(paramsJson));
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            String json = rs.getString(1);
            if (json != null) {
              JsonNode node = MAPPER.readTree(json);
              String uiId = buildUiId(node);
              if (node.has("id")) idToUiId.put(node.get("id").asLong(), uiId);
              nodesMap.computeIfAbsent(uiId, k -> buildUiNode(node));
            }
          }
        }
      } catch (SQLException e) {
        log.warn(
            "Start node fetch failed for label={} fqn={}: {}", startLabel, fqn, e.getMessage());
      }
    }

    // Step 2: Fetch connected nodes via each edge type (depth 1..N).
    // AGE 1.5.0 limitations:
    //   - No edge-type alternation [:T1|T2] — run two separate queries.
    //   - No label predicates in WHERE (other:Job OR other:Dataset) — omit the WHERE clause;
    //     INPUT_TO and PRODUCES edges connect only Job/Dataset nodes by schema.
    //   - No list concatenation collect()+collect() — collect only `other` nodes here;
    //     start node is already fetched above.
    for (String edgeType : new String[] {"INPUT_TO", "PRODUCES"}) {
      String nodesSql =
          String.format(
              "SELECT agtype_to_json(n) "
                  + "FROM %scypher('marquez_graph', $$ "
                  + "MATCH (:%s {fqn: $fqn})-[:%s*1..%d]-(n) "
                  + "RETURN DISTINCT n "
                  + "LIMIT 500 "
                  + "$$, ?) as (n %sagtype)",
              GraphDao.prefix(), startLabel, edgeType, depth, GraphDao.prefix());

      try (PreparedStatement ps = conn.prepareStatement(nodesSql)) {
        ps.setObject(1, GraphDao.createAgtype(paramsJson));
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            String json = rs.getString(1);
            if (json == null) continue;
            JsonNode node = MAPPER.readTree(json);
            String uiId = buildUiId(node);
            if (node.has("id")) idToUiId.put(node.get("id").asLong(), uiId);
            nodesMap.computeIfAbsent(uiId, k -> buildUiNode(node));
          }
        }
      } catch (SQLException e) {
        log.warn("Node traversal failed for edgeType={} fqn={}: {}", edgeType, fqn, e.getMessage());
      }
    }

    // Collect edges for each edge type separately (same reason: no | syntax).
    // Variable-length match gives `rels` as a list; UNWIND extracts individual edge agtypes.
    if (nodesMap.size() > 1) {
      for (String edgeType : new String[] {"INPUT_TO", "PRODUCES"}) {
        String edgesSql =
            String.format(
                "SELECT agtype_to_json(rel) "
                    + "FROM %scypher('marquez_graph', $$ "
                    + "MATCH (:%s {fqn: $fqn})-[rels:%s*1..%d]-(n) "
                    + "UNWIND rels AS rel "
                    + "RETURN DISTINCT rel "
                    + "LIMIT 1000 "
                    + "$$, ?) as (rel %sagtype)",
                GraphDao.prefix(), startLabel, edgeType, depth, GraphDao.prefix());

        try (PreparedStatement ps = conn.prepareStatement(edgesSql)) {
          ps.setObject(1, GraphDao.createAgtype(paramsJson));
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              String json = rs.getString(1);
              if (json == null) continue;
              JsonNode edge = MAPPER.readTree(json);
              long startId = edge.has("start_id") ? edge.get("start_id").asLong() : -1;
              long endId = edge.has("end_id") ? edge.get("end_id").asLong() : -1;
              String startUiId = idToUiId.get(startId);
              String endUiId = idToUiId.get(endId);
              if (startUiId != null && endUiId != null) {
                if (nodesMap.containsKey(startUiId))
                  addEdge(nodesMap.get(startUiId), startUiId, endUiId, "out");
                if (nodesMap.containsKey(endUiId))
                  addEdge(nodesMap.get(endUiId), startUiId, endUiId, "in");
              }
            }
          }
        } catch (SQLException e) {
          log.warn(
              "Edge traversal failed for edgeType={} fqn={}: {}", edgeType, fqn, e.getMessage());
        }
      }
    }

    // Populate data.inputs / data.outputs on each node from collected edges.
    // For a Job: inEdges origins = input datasets, outEdges destinations = output datasets.
    // For a Dataset: inEdges origins = producing jobs, outEdges destinations = consuming jobs.
    for (ObjectNode uiNode : nodesMap.values()) {
      ObjectNode data = (ObjectNode) uiNode.get("data");
      ArrayNode inEdges = (ArrayNode) uiNode.get("inEdges");
      ArrayNode outEdges = (ArrayNode) uiNode.get("outEdges");
      ArrayNode inputs = MAPPER.createArrayNode();
      ArrayNode outputs = MAPPER.createArrayNode();
      if (inEdges != null) {
        for (JsonNode edge : inEdges) {
          String origin = edge.path("origin").asText();
          ObjectNode other = nodesMap.get(origin);
          if (other != null) {
            ObjectNode ref = MAPPER.createObjectNode();
            ref.put("namespace", other.path("data").path("namespace").asText());
            ref.put("name", other.path("data").path("name").asText());
            inputs.add(ref);
          }
        }
      }
      if (outEdges != null) {
        for (JsonNode edge : outEdges) {
          String dest = edge.path("destination").asText();
          ObjectNode other = nodesMap.get(dest);
          if (other != null) {
            ObjectNode ref = MAPPER.createObjectNode();
            ref.put("namespace", other.path("data").path("namespace").asText());
            ref.put("name", other.path("data").path("name").asText());
            outputs.add(ref);
          }
        }
      }
      data.set("inputs", inputs);
      data.set("outputs", outputs);
    }

    // Batch-fetch schema fields for all Dataset nodes in one query
    fetchDatasetFields(conn, nodesMap);

    return Response.ok(Map.of("graph", nodesMap.values())).build();
  }

  // ---------------------------------------------------------------------------
  // Run lineage (with optional parent-run aggregation)
  // ---------------------------------------------------------------------------

  /**
   * Returns lineage for a specific run. When {@code aggregateToParentRun=true}, traverses up to the
   * root ancestor and aggregates all descendant run inputs/outputs, mirroring the V1/V2 behaviour
   * for Spark/Databricks parent-child run hierarchies.
   */
  private Response getRunLineage(Connection conn, String runId, boolean aggregateToParentRun)
      throws Exception {

    if (aggregateToParentRun) {
      return getAggregatedParentRunLineage(conn, runId);
    }

    // Simple single-run lineage
    String paramsJson = MAPPER.writeValueAsString(Map.of("runId", runId));

    String runSql =
        String.format(
            "SELECT agtype_to_json(n) "
                + "FROM %scypher('marquez_graph', $$ "
                + "MATCH (r:Run {runId: $runId}) RETURN properties(r) "
                + "$$, ?) as (n %sagtype)",
            GraphDao.prefix(), GraphDao.prefix());

    ObjectNode runNode = null;
    try (PreparedStatement ps = conn.prepareStatement(runSql)) {
      ps.setObject(1, GraphDao.createAgtype(paramsJson));
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          runNode = (ObjectNode) MAPPER.readTree(rs.getString(1));
        }
      }
    }

    if (runNode == null) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }

    // Parse facets string → JSON object
    if (runNode.has("facets") && runNode.get("facets").isTextual()) {
      try {
        runNode.set("facets", MAPPER.readTree(runNode.get("facets").asText()));
      } catch (Exception ignored) {
      }
    }

    List<JsonNode> inputs = fetchRunDatasetVersions(conn, runId, "READS");
    List<JsonNode> outputs = fetchRunDatasetVersions(conn, runId, "WRITES");

    runNode.set("inputs", MAPPER.valueToTree(inputs));
    runNode.set("outputs", MAPPER.valueToTree(outputs));

    return Response.ok(Map.of("run", runNode)).build();
  }

  /**
   * Aggregates all child runs under the given run's ancestor, unioning inputs/outputs. State
   * priority: FAIL > ABORT > RUNNING > COMPLETE > START.
   */
  private Response getAggregatedParentRunLineage(Connection conn, String runId) throws Exception {
    // Find the top-level ancestor
    String ancestorId = findRootAncestor(conn, runId);

    String paramsJson = MAPPER.writeValueAsString(Map.of("parentId", ancestorId));

    // Collect all descendant runs
    String childRunsSql =
        String.format(
            "SELECT agtype_to_json(n) "
                + "FROM %scypher('marquez_graph', $$ "
                + "MATCH (parent:Run {runId: $parentId})-[:HAS_CHILD_RUN*0..]->(c:Run) "
                + "RETURN properties(c) "
                + "$$, ?) as (n %sagtype)",
            GraphDao.prefix(), GraphDao.prefix());

    List<JsonNode> allRuns = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(childRunsSql)) {
      ps.setObject(1, GraphDao.createAgtype(paramsJson));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          allRuns.add(MAPPER.readTree(rs.getString(1)));
        }
      }
    }

    if (allRuns.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }

    // Aggregate state across all runs
    String aggregatedState = aggregateState(allRuns);

    // Union all inputs and outputs
    List<JsonNode> allInputs = new ArrayList<>();
    List<JsonNode> allOutputs = new ArrayList<>();
    for (JsonNode run : allRuns) {
      String rid = run.has("runId") ? run.get("runId").asText() : null;
      if (rid != null) {
        allInputs.addAll(fetchRunDatasetVersions(conn, rid, "READS"));
        allOutputs.addAll(fetchRunDatasetVersions(conn, rid, "WRITES"));
      }
    }

    // Build aggregated response matching V1 shape
    ObjectNode aggregated = MAPPER.createObjectNode();
    aggregated.put("runId", ancestorId);
    aggregated.put("state", aggregatedState);
    aggregated.put("aggregatedToParentRun", true);
    aggregated.put("childRunCount", allRuns.size());
    aggregated.set("inputs", deduplicateDatasetVersions(allInputs));
    aggregated.set("outputs", deduplicateDatasetVersions(allOutputs));

    return Response.ok(Map.of("run", aggregated)).build();
  }

  /**
   * Walks up {@code HAS_CHILD_RUN} edges in reverse to find the topmost ancestor of {@code runId}.
   * AGE 1.5.0 does not support anonymous node patterns in WHERE (e.g. WHERE NOT ()-[:R]->(n)), so
   * we collect all ancestors via a reverse traversal, order by path depth, and take the last. If
   * the run has no parent the run itself is returned.
   */
  private String findRootAncestor(Connection conn, String runId) throws Exception {
    String paramsJson = MAPPER.writeValueAsString(Map.of("runId", runId));
    // Traverse upward: collect all runs from which runId is reachable via HAS_CHILD_RUN*
    String sql =
        String.format(
            "SELECT agtype_to_json(n) "
                + "FROM %scypher('marquez_graph', $$ "
                + "MATCH (ancestor:Run)-[:HAS_CHILD_RUN*1..]->(r:Run {runId: $runId}) "
                + "RETURN properties(ancestor) "
                + "$$, ?) as (n %sagtype)",
            GraphDao.prefix(), GraphDao.prefix());

    String topAncestorId = runId;
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setObject(1, GraphDao.createAgtype(paramsJson));
      try (ResultSet rs = ps.executeQuery()) {
        // The last ancestor returned is the furthest from runId (variable-length traversal
        // returns paths in order of increasing length, so the final row = root).
        while (rs.next()) {
          JsonNode node = MAPPER.readTree(rs.getString(1));
          if (node != null && node.has("runId")) {
            topAncestorId = node.get("runId").asText();
          }
        }
      }
    } catch (SQLException e) {
      log.debug("findRootAncestor: no parent found for run {}: {}", runId, e.getMessage());
    }
    return topAncestorId;
  }

  // ---------------------------------------------------------------------------
  // Helpers – dataset version fetching
  // ---------------------------------------------------------------------------

  /** Fetches all DatasetVersion nodes connected to a run via the given edge type. */
  private List<JsonNode> fetchRunDatasetVersions(Connection conn, String runId, String edgeType)
      throws Exception {
    String paramsJson = MAPPER.writeValueAsString(Map.of("runId", runId));
    String sql =
        String.format(
            "SELECT agtype_to_json(dv_props), agtype_to_json(d_props) "
                + "FROM %scypher('marquez_graph', $$ "
                + "MATCH (r:Run {runId: $runId})-[:%s]->(dv:DatasetVersion)-[:VERSION_OF]->(d:Dataset) "
                + "RETURN properties(dv), properties(d) "
                + "$$, ?) as (dv_props %sagtype, d_props %sagtype)",
            GraphDao.prefix(), edgeType, GraphDao.prefix(), GraphDao.prefix());

    List<JsonNode> results = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setObject(1, GraphDao.createAgtype(paramsJson));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String dv_json = rs.getString(1);
          ObjectNode dv =
              dv_json == null ? MAPPER.createObjectNode() : (ObjectNode) MAPPER.readTree(dv_json);
          String d_json = rs.getString(2);
          ObjectNode d =
              d_json == null ? MAPPER.createObjectNode() : (ObjectNode) MAPPER.readTree(d_json);
          // Parse facets string → JSON object
          if (dv.has("facets") && dv.get("facets").isTextual()) {
            try {
              dv.set("facets", MAPPER.readTree(dv.get("facets").asText()));
            } catch (Exception ignored) {
            }
          }
          ObjectNode combined = MAPPER.createObjectNode();
          combined.setAll(dv);
          combined.put("datasetName", d.has("name") ? d.get("name").asText() : "");
          combined.put("datasetNamespace", d.has("namespace") ? d.get("namespace").asText() : "");
          combined.put("datasetType", d.has("type") ? d.get("type").asText() : "DB_TABLE");
          results.add(combined);
        }
      }
    } catch (SQLException e) {
      log.debug(
          "fetchRunDatasetVersions: edge type {} may not exist yet: {}", edgeType, e.getMessage());
    }
    return results;
  }

  /** Deduplicates dataset versions by UUID, keeping the first occurrence. */
  private ArrayNode deduplicateDatasetVersions(List<JsonNode> versions) {
    ArrayNode arr = MAPPER.createArrayNode();
    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
    for (JsonNode v : versions) {
      String uuid = v.has("uuid") ? v.get("uuid").asText() : v.toString();
      if (seen.add(uuid)) {
        arr.add(v);
      }
    }
    return arr;
  }

  // ---------------------------------------------------------------------------
  // Helpers – path processing
  // ---------------------------------------------------------------------------

  /**
   * Processes a single AGE path result, extracting {@code Job} and {@code Dataset} nodes and
   * building {@code inEdges} / {@code outEdges} between them.
   */
  private void processPath(JsonNode path, Map<String, ObjectNode> nodesMap) {
    if (path == null || !path.isArray()) return;

    Map<String, String> internalToUiId = new HashMap<>();

    // Pass 1: collect Job and Dataset nodes
    for (JsonNode item : path) {
      if (!item.has("label")) continue; // not a node
      String label = item.get("label").asText();
      if (!"Job".equals(label) && !"Dataset".equals(label)) continue;

      String uiId = buildUiId(item);
      String internalId = item.has("id") ? item.get("id").asText() : uiId;
      internalToUiId.put(internalId, uiId);
      nodesMap.computeIfAbsent(uiId, k -> buildUiNode(item));
    }

    // Pass 2: synthesise edges between Job and Dataset nodes
    for (JsonNode item : path) {
      if (!item.has("start_id")) continue; // not an edge
      String startId = item.get("start_id").asText();
      String endId = item.get("end_id").asText();
      String startUiId = internalToUiId.get(startId);
      String endUiId = internalToUiId.get(endId);
      if (startUiId != null && endUiId != null && nodesMap.containsKey(startUiId)) {
        addEdge(nodesMap.get(startUiId), startUiId, endUiId, "out");
        if (nodesMap.containsKey(endUiId)) {
          addEdge(nodesMap.get(endUiId), startUiId, endUiId, "in");
        }
      }
    }
  }

  /** Fetches a single node by label and FQN when the start node has no lineage edges. */
  private void fetchSingleNode(
      Connection conn, String label, String fqn, Map<String, ObjectNode> nodesMap)
      throws Exception {
    String paramsJson = MAPPER.writeValueAsString(Map.of("fqn", fqn));
    String sql =
        String.format(
            "SELECT agtype_to_json(n) "
                + "FROM %scypher('marquez_graph', $$ "
                + "MATCH (n:%s {fqn: $fqn}) RETURN n "
                + "LIMIT 1 "
                + "$$, ?) as (n %sagtype)",
            GraphDao.prefix(), label, GraphDao.prefix());

    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setObject(1, GraphDao.createAgtype(paramsJson));
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          JsonNode node = MAPPER.readTree(rs.getString(1));
          if (node != null) {
            String uiId = buildUiId(node);
            nodesMap.putIfAbsent(uiId, buildUiNode(node));
          }
        }
      }
    }
  }

  /**
   * Batch-fetches schema fields for all Dataset nodes in {@code nodesMap} using a single Cypher
   * query (avoids N+1 per-dataset queries).
   */
  private void fetchDatasetFields(Connection conn, Map<String, ObjectNode> nodesMap)
      throws Exception {
    List<String> datasetFqns = new ArrayList<>();
    for (ObjectNode n : nodesMap.values()) {
      if ("DATASET".equals(n.path("type").asText())) {
        String fqn = n.path("data").path("fqn").asText();
        if (!fqn.isBlank()) datasetFqns.add(fqn);
      }
    }
    if (datasetFqns.isEmpty()) return;

    // Fetch fields for all datasets in one round trip
    for (String fqn : datasetFqns) {
      String paramsJson = MAPPER.writeValueAsString(Map.of("fqn", fqn));
      String sql =
          String.format(
              "SELECT agtype_to_json(n) "
                  + "FROM %scypher('marquez_graph', $$ "
                  + "MATCH (d:Dataset {fqn: $fqn})-[:HAS_DATASET_VERSION]->(dv:DatasetVersion)"
                  + "      -[:HAS_FIELD]->(f:DatasetField) "
                  + "RETURN properties(f) "
                  + "$$, ?) as (n %sagtype)",
              GraphDao.prefix(), GraphDao.prefix());

      String uiId = "dataset:" + fqn;
      ObjectNode uiNode = nodesMap.get(uiId);
      if (uiNode == null) continue;
      ArrayNode fields = (ArrayNode) uiNode.path("data").path("fields");

      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setObject(1, GraphDao.createAgtype(paramsJson));
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            JsonNode f = MAPPER.readTree(rs.getString(1));
            if (f != null) fields.add(f);
          }
        }
      } catch (SQLException e) {
        log.debug("No fields for dataset '{}': {}", fqn, e.getMessage());
      }
    }
  }

  // ---------------------------------------------------------------------------
  // UI node construction – matches frontend LineageNode TypeScript interface
  // ---------------------------------------------------------------------------

  /**
   * Builds a UI node object that satisfies the frontend's {@code LineageNode} TypeScript interface:
   *
   * <pre>
   * { id: string, type: "JOB"|"DATASET", data: {...}, inEdges: [], outEdges: [] }
   * </pre>
   */
  private ObjectNode buildUiNode(JsonNode rawNode) {
    String label = rawNode.has("label") ? rawNode.get("label").asText() : "Job";
    JsonNode props = rawNode.has("properties") ? rawNode.get("properties") : rawNode;

    String fqn =
        props.has("fqn")
            ? props.get("fqn").asText()
            : props.has("name") ? props.get("name").asText() : "";
    String name = props.has("name") ? props.get("name").asText() : lastSegment(fqn);
    String namespace = props.has("namespace") ? props.get("namespace").asText() : "default";

    ObjectNode data = MAPPER.createObjectNode();
    // Copy all raw properties first, then override/add required fields
    if (props.isObject()) {
      data.setAll((ObjectNode) props);
    }
    // Normalise facets: if stored as a JSON string, parse it
    if (data.has("facets") && data.get("facets").isTextual()) {
      try {
        data.set("facets", MAPPER.readTree(data.get("facets").asText()));
      } catch (Exception ignored) {
        data.set("facets", MAPPER.createObjectNode());
      }
    } else if (!data.has("facets")) {
      data.set("facets", MAPPER.createObjectNode());
    }

    data.put("fqn", fqn);
    data.put("name", name);
    data.put("namespace", namespace);
    data.put(
        "simpleName",
        props.has("simpleName") ? props.get("simpleName").asText() : simpleName(name));

    // id sub-object required by frontend
    ObjectNode id = MAPPER.createObjectNode();
    id.put("namespace", namespace);
    id.put("name", name);
    data.set("id", id);

    // inputs / outputs populated from edges (not graph traversal here)
    data.set("inputs", MAPPER.createArrayNode());
    data.set("outputs", MAPPER.createArrayNode());

    if ("Dataset".equals(label)) {
      data.put(
          "physicalName", props.has("physicalName") ? props.get("physicalName").asText() : name);
      data.put(
          "sourceName", props.has("sourceName") ? props.get("sourceName").asText() : "default");
      data.put("type", props.has("type") ? props.get("type").asText() : "DB_TABLE");
      data.set("fields", MAPPER.createArrayNode());
      if (!data.has("lastModifiedAt")) {
        data.put("lastModifiedAt", data.has("updatedAt") ? data.get("updatedAt").asText() : "");
      }
    } else {
      // Job
      data.put("type", props.has("type") ? props.get("type").asText() : "BATCH");
      data.put("location", props.has("location") ? props.get("location").asText() : "");
      data.put(
          "parentJobName", props.has("parentJobName") ? props.get("parentJobName").asText() : null);
      data.put(
          "parentJobUuid", props.has("parentJobUuid") ? props.get("parentJobUuid").asText() : null);
      data.set("latestRun", MAPPER.nullNode());
    }

    if (!data.has("tags")) data.set("tags", MAPPER.createArrayNode());
    if (!data.has("description")) data.put("description", "");
    if (!data.has("createdAt")) data.put("createdAt", "");
    if (!data.has("updatedAt")) data.put("updatedAt", "");

    ObjectNode uiNode = MAPPER.createObjectNode();
    uiNode.put("id", buildUiId(rawNode));
    uiNode.put("type", label.toUpperCase());
    uiNode.set("data", data);
    uiNode.set("inEdges", MAPPER.createArrayNode());
    uiNode.set("outEdges", MAPPER.createArrayNode());
    return uiNode;
  }

  /** Builds the UI node id string ({@code label:fqn}) from a raw AGE node. */
  private String buildUiId(JsonNode node) {
    String label = node.has("label") ? node.get("label").asText().toLowerCase() : "job";
    JsonNode props = node.has("properties") ? node.get("properties") : node;
    String fqn =
        props.has("fqn")
            ? props.get("fqn").asText()
            : props.has("name") ? props.get("name").asText() : "";
    return label + ":" + fqn;
  }

  private void addEdge(ObjectNode node, String origin, String destination, String direction) {
    ArrayNode edges = (ArrayNode) node.get(direction + "Edges");
    if (edges == null) return;
    for (JsonNode e : edges) {
      if (origin.equals(e.path("origin").asText())
          && destination.equals(e.path("destination").asText())) {
        return; // already present
      }
    }
    ObjectNode edge = MAPPER.createObjectNode();
    edge.put("origin", origin);
    edge.put("destination", destination);
    edges.add(edge);
  }

  // ---------------------------------------------------------------------------
  // State aggregation for parent-run queries
  // ---------------------------------------------------------------------------

  /**
   * Aggregates child run states into a single representative state following priority: {@code FAIL}
   * > {@code ABORT} > {@code RUNNING} > {@code COMPLETE} > {@code START}.
   */
  static String aggregateState(List<JsonNode> runs) {
    boolean hasFail = false;
    boolean hasAbort = false;
    boolean hasRunning = false;
    boolean hasComplete = false;

    for (JsonNode run : runs) {
      String state = run.has("state") ? run.get("state").asText().toUpperCase() : "START";
      switch (state) {
        case "FAIL":
        case "FAILED":
          hasFail = true;
          break;
        case "ABORT":
        case "ABORTED":
          hasAbort = true;
          break;
        case "RUNNING":
          hasRunning = true;
          break;
        case "COMPLETE":
        case "COMPLETED":
          hasComplete = true;
          break;
        default:
          break;
      }
    }

    if (hasFail) return "FAILED";
    if (hasAbort) return "ABORTED";
    if (hasRunning) return "RUNNING";
    if (hasComplete) return "COMPLETE";
    return "RUNNING";
  }

  // ---------------------------------------------------------------------------
  // String helpers
  // ---------------------------------------------------------------------------

  /** Returns the last {@code /}-separated segment of a name. */
  private static String simpleName(String name) {
    if (name == null || name.isBlank()) return "";
    int idx = name.lastIndexOf('/');
    return idx >= 0 ? name.substring(idx + 1) : name;
  }

  /** Returns the part after the last {@code :} separator (used for namespace:name FQNs). */
  private static String lastSegment(String fqn) {
    if (fqn == null || fqn.isBlank()) return "";
    int idx = fqn.lastIndexOf(':');
    return idx >= 0 ? fqn.substring(idx + 1) : fqn;
  }
}

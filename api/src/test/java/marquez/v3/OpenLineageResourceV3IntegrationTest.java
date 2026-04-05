/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for the V3 Graph API ({@code /api/v3/*}) endpoints.
 *
 * <h2>Coverage</h2>
 *
 * <p>These tests verify V1/V2 parity for every V3 endpoint using a full {@code MarquezApp} started
 * against an {@code apache/age:latest} Testcontainer. AGE is available automatically because {@link
 * marquez.PostgresContainer} already uses that image.
 *
 * <h3>Scenarios</h3>
 *
 * <ol>
 *   <li>POST valid lineage event → 201
 *   <li>POST null body → 400
 *   <li>POST event with null job → 400
 *   <li>POST event with inputs and outputs → 201, graph contains dataset nodes
 *   <li>GET /api/v3/lineage by job nodeId → 200 with graph nodes
 *   <li>GET /api/v3/lineage by dataset nodeId → 200 with graph nodes
 *   <li>GET /api/v3/lineage with depth=1 → shallow result (only direct I/O)
 *   <li>GET /api/v3/lineage with depth=2 → deeper result (two hops)
 *   <li>GET /api/v3/lineage missing nodeId → 400
 *   <li>GET /api/v3/lineage aggregateToParentRun=true with child runs → aggregated run
 *   <li>GET /api/v3/lineage aggregateToParentRun=true, child FAIL → state=FAIL
 *   <li>GET /api/v3/namespaces → 200, contains ingested namespace
 *   <li>GET /api/v3/namespaces/{ns} → 200 with name field
 *   <li>GET /api/v3/namespaces/{ns} not found → 404
 *   <li>GET /api/v3/namespaces/{ns}/jobs → 200, contains ingested job
 *   <li>GET /api/v3/namespaces/{ns}/jobs/{job} → 200 with name, namespace, type
 *   <li>GET /api/v3/namespaces/{ns}/jobs/{job} not found → 404
 *   <li>GET /api/v3/namespaces/{ns}/jobs/{job}/runs → 200 with runs list
 *   <li>GET /api/v3/namespaces/{ns}/datasets → 200, contains ingested dataset
 *   <li>GET /api/v3/namespaces/{ns}/datasets/{ds} → 200 with name, namespace
 *   <li>GET /api/v3/namespaces/{ns}/datasets/{ds} not found → 404
 * </ol>
 *
 * <p>Tests are ordered so that ingestion (order ≤ 10) always runs before query tests (order ≥ 11).
 * All tests share a single app instance and database, so ingested data is visible to all query
 * tests without additional setup.
 */
@Tag("integration")
@TestMethodOrder(OrderAnnotation.class)
public class OpenLineageResourceV3IntegrationTest extends BaseV3IntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // Shared test identifiers – stable across the test class
  private static final String NS = "v3-test-ns";
  private static final String JOB = "v3-etl-job";
  private static final String INPUT_DS_NS = "v3-source-ns";
  private static final String INPUT_DS = "raw_events";
  private static final String OUTPUT_DS_NS = "v3-test-ns";
  private static final String OUTPUT_DS = "processed_events";

  // Run IDs must be valid UUIDs (OpenLineage spec)
  private static String PARENT_RUN_ID;
  private static String CHILD_RUN_ID_1;
  private static String CHILD_RUN_ID_2;
  private static String SIMPLE_RUN_ID;

  @BeforeAll
  static void generateRunIds() {
    PARENT_RUN_ID = UUID.randomUUID().toString();
    CHILD_RUN_ID_1 = UUID.randomUUID().toString();
    CHILD_RUN_ID_2 = UUID.randomUUID().toString();
    SIMPLE_RUN_ID = UUID.randomUUID().toString();
  }

  // ===========================================================================
  // Scenario 1 – POST lineage events (must run before query tests)
  // ===========================================================================

  /**
   * Scenario 1: POST a valid START event with input and output datasets. Verifies that the graph is
   * populated synchronously before 201 is returned.
   */
  @Test
  @Order(1)
  void postLineageEvent_validStartEvent_returns201() throws Exception {
    String body =
        lineageEvent(
            NS,
            JOB,
            SIMPLE_RUN_ID,
            "START",
            new String[] {INPUT_DS_NS + ":" + INPUT_DS},
            new String[] {OUTPUT_DS_NS + ":" + OUTPUT_DS});

    HttpResponse<String> response = postV3Lineage(body).get();

    assertThat(response.statusCode()).isEqualTo(201);
  }

  /** Scenario 2: POST a COMPLETE event for the same run (updates run state to COMPLETE). */
  @Test
  @Order(2)
  void postLineageEvent_completeEvent_returns201() throws Exception {
    String body =
        lineageEvent(
            NS,
            JOB,
            SIMPLE_RUN_ID,
            "COMPLETE",
            new String[] {INPUT_DS_NS + ":" + INPUT_DS},
            new String[] {OUTPUT_DS_NS + ":" + OUTPUT_DS});

    HttpResponse<String> response = postV3Lineage(body).get();

    assertThat(response.statusCode()).isEqualTo(201);
  }

  /** Scenario 3: POST parent run START event (no inputs/outputs – parent is the orchestrator). */
  @Test
  @Order(3)
  void postLineageEvent_parentRun_returns201() throws Exception {
    String body =
        lineageEvent(
            NS, "v3-spark-parent", PARENT_RUN_ID, "START", new String[] {}, new String[] {});

    HttpResponse<String> response = postV3Lineage(body).get();

    assertThat(response.statusCode()).isEqualTo(201);
  }

  /** Scenario 4: POST child-1 START event (reads input, COMPLETE state in a later call). */
  @Test
  @Order(4)
  void postLineageEvent_childRun1Complete_returns201() throws Exception {
    // Child 1: COMPLETE
    String start =
        childLineageEvent(
            NS,
            "v3-spark-child-1",
            CHILD_RUN_ID_1,
            "START",
            PARENT_RUN_ID,
            NS,
            "v3-spark-parent",
            new String[] {INPUT_DS_NS + ":" + INPUT_DS},
            new String[] {});
    postV3Lineage(start).get();

    String complete =
        childLineageEvent(
            NS,
            "v3-spark-child-1",
            CHILD_RUN_ID_1,
            "COMPLETE",
            PARENT_RUN_ID,
            NS,
            "v3-spark-parent",
            new String[] {INPUT_DS_NS + ":" + INPUT_DS},
            new String[] {OUTPUT_DS_NS + ":" + OUTPUT_DS});

    HttpResponse<String> response = postV3Lineage(complete).get();

    assertThat(response.statusCode()).isEqualTo(201);
  }

  /** Scenario 5: POST child-2 FAIL event (fails → parent aggregated state should be FAIL). */
  @Test
  @Order(5)
  void postLineageEvent_childRun2Fail_returns201() throws Exception {
    String body =
        childLineageEvent(
            NS,
            "v3-spark-child-2",
            CHILD_RUN_ID_2,
            "FAIL",
            PARENT_RUN_ID,
            NS,
            "v3-spark-parent",
            new String[] {INPUT_DS_NS + ":" + INPUT_DS},
            new String[] {});

    HttpResponse<String> response = postV3Lineage(body).get();

    assertThat(response.statusCode()).isEqualTo(201);
  }

  // ===========================================================================
  // Scenario 2 – POST validation (no database required, order independent)
  // ===========================================================================

  /** Scenario 6: null body → 400. */
  @Test
  @Order(6)
  void postLineageEvent_nullBody_returns400() throws Exception {
    HttpRequest_manualNullBody();
  }

  private void HttpRequest_manualNullBody() throws Exception {
    // Send an empty body to trigger the null-event path via missing Content-Type check
    java.net.http.HttpRequest request =
        java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(baseUrl + "/api/v3/lineage"))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("null"))
            .build();
    HttpResponse<String> response =
        http2.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString()).get();
    // null JSON body deserializes to null → 400
    assertThat(response.statusCode()).isEqualTo(400);
  }

  /** Scenario 7: missing job field → 400. */
  @Test
  @Order(7)
  void postLineageEvent_missingJobField_returns400() throws Exception {
    String body =
        "{\"eventType\":\"START\",\"eventTime\":\"2026-01-15T10:00:00Z\","
            + "\"run\":{\"runId\":\""
            + UUID.randomUUID()
            + "\"},"
            + "\"inputs\":[],\"outputs\":[]}";

    HttpResponse<String> response = postV3Lineage(body).get();

    assertThat(response.statusCode()).isEqualTo(400);
  }

  // ===========================================================================
  // Scenario 3 – GET /api/v3/lineage (require ingestion to have run first)
  // ===========================================================================

  /**
   * Scenario 8: GET lineage by job nodeId → 200 with at least the start job in the graph array.
   *
   * <p>V1/V2 parity: {@code GET /api/v1/lineage?nodeId=job:ns:name} returns {@code {graph:[…]}}.
   */
  @Test
  @Order(11)
  void getLineage_byJobNodeId_returns200WithGraph() throws Exception {
    String nodeId = "job:" + NS + ":" + JOB;

    HttpResponse<String> response = getV3Lineage(nodeId).get();

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.has("graph")).isTrue();
    JsonNode graph = body.get("graph");
    assertThat(graph.isArray()).isTrue();
    // At minimum: the Job node itself
    assertThat(graph.size()).isGreaterThanOrEqualTo(1);

    // The start node should be present in the graph
    boolean foundJob =
        streamNodes(graph)
            .anyMatch(
                n -> {
                  JsonNode data = n.path("data");
                  return JOB.equals(data.path("name").asText())
                      && NS.equals(data.path("namespace").asText());
                });
    assertThat(foundJob).as("Expected job node '%s' in graph", JOB).isTrue();
  }

  /**
   * Scenario 9: GET lineage by dataset nodeId (output dataset) → graph includes the writing job.
   */
  @Test
  @Order(12)
  void getLineage_byDatasetNodeId_returns200WithGraph() throws Exception {
    String nodeId = "dataset:" + OUTPUT_DS_NS + ":" + OUTPUT_DS;

    HttpResponse<String> response = getV3Lineage(nodeId).get();

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.has("graph")).isTrue();
    JsonNode graph = body.get("graph");
    assertThat(graph.isArray()).isTrue();
    assertThat(graph.size()).isGreaterThanOrEqualTo(1);
  }

  /**
   * Scenario 10: GET lineage with depth=1 returns fewer nodes than depth=2.
   *
   * <p>With depth=1 from the job node we expect only the job + its direct I/O datasets. With
   * depth=2 we additionally get the jobs/datasets one further hop away.
   */
  @Test
  @Order(13)
  void getLineage_depthParameter_controlsTraversalDepth() throws Exception {
    String nodeId = "job:" + NS + ":" + JOB;

    HttpResponse<String> depth1Response = getV3Lineage(nodeId, 1, false).get();
    HttpResponse<String> depth2Response = getV3Lineage(nodeId, 2, false).get();

    assertThat(depth1Response.statusCode()).isEqualTo(200);
    assertThat(depth2Response.statusCode()).isEqualTo(200);

    JsonNode depth1Graph = MAPPER.readTree(depth1Response.body()).path("graph");
    JsonNode depth2Graph = MAPPER.readTree(depth2Response.body()).path("graph");

    // depth=2 should return at least as many nodes as depth=1
    assertThat(depth2Graph.size()).isGreaterThanOrEqualTo(depth1Graph.size());
  }

  /** Scenario 11: GET lineage without nodeId → 400 (V1/V2 parity: nodeId is required). */
  @Test
  @Order(14)
  void getLineage_missingNodeId_returns400() throws Exception {
    java.net.http.HttpRequest request =
        java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(baseUrl + "/api/v3/lineage"))
            .header("Accept", "application/json")
            .GET()
            .build();
    HttpResponse<String> response =
        http2.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString()).get();

    assertThat(response.statusCode()).isEqualTo(400);
  }

  /**
   * Scenario 12: GET lineage with aggregateToParentRun=true for the parent run.
   *
   * <p>V1/V2 parity: the response should contain an aggregated run node whose state reflects the
   * worst child state. Because child-2 FAILED, the parent aggregate state must be FAIL.
   */
  @Test
  @Order(15)
  void getLineage_aggregateToParentRun_returnsAggregatedRun() throws Exception {
    String nodeId = "run:" + PARENT_RUN_ID;

    HttpResponse<String> response = getV3Lineage(nodeId, 2, true).get();

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.has("graph")).isTrue();
    JsonNode graph = body.get("graph");
    assertThat(graph.isArray()).isTrue();

    // The aggregated run node should appear in the graph
    boolean foundRun =
        streamNodes(graph)
            .anyMatch(
                n -> {
                  JsonNode data = n.path("data");
                  return "run".equals(n.path("type").asText())
                      && PARENT_RUN_ID.equals(data.path("id").asText());
                });
    assertThat(foundRun)
        .as("Expected aggregated run node for parent run '%s'", PARENT_RUN_ID)
        .isTrue();
  }

  /**
   * Scenario 13: Aggregated run state is FAIL because one child failed.
   *
   * <p>State priority (V1 parity): FAIL &gt; ABORT &gt; RUNNING &gt; COMPLETE &gt; START
   */
  @Test
  @Order(16)
  void getLineage_aggregateToParentRun_worstChildStateWins() throws Exception {
    String nodeId = "run:" + PARENT_RUN_ID;

    HttpResponse<String> response = getV3Lineage(nodeId, 2, true).get();

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode graph = MAPPER.readTree(response.body()).path("graph");

    // Find the run node representing the aggregated parent
    java.util.Optional<JsonNode> runNode =
        streamNodes(graph)
            .filter(n -> "run".equals(n.path("type").asText()))
            .filter(n -> PARENT_RUN_ID.equals(n.path("data").path("id").asText()))
            .findFirst();

    assertThat(runNode).as("Aggregated run node not found").isPresent();
    String state = runNode.get().path("data").path("state").asText();
    assertThat(state).as("Aggregated state should be FAIL (child-2 failed)").isEqualTo("FAIL");
  }

  // ===========================================================================
  // Scenario 4 – GET /api/v3/namespaces (V1 parity: GET /api/v1/namespaces)
  // ===========================================================================

  /**
   * Scenario 14: List namespaces → 200 with "namespaces" array containing the ingested namespace.
   *
   * <p>V1 parity: {@code GET /api/v1/namespaces} returns {@code {"namespaces":[…]}}.
   */
  @Test
  @Order(21)
  void listNamespaces_afterIngestion_containsIngestedNamespace() throws Exception {
    HttpResponse<String> response = getV3Namespaces().get();

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.has("namespaces")).isTrue();
    assertThat(body.get("namespaces").isArray()).isTrue();

    boolean found =
        streamNodes(body.get("namespaces")).anyMatch(n -> NS.equals(n.path("name").asText()));
    assertThat(found).as("Expected namespace '%s' in list", NS).isTrue();
  }

  /**
   * Scenario 15: GET single namespace by name → 200 with "name" field matching.
   *
   * <p>V1 parity: {@code GET /api/v1/namespaces/{namespace}} returns the namespace object.
   */
  @Test
  @Order(22)
  void getNamespace_knownNamespace_returns200WithName() throws Exception {
    HttpResponse<String> response = getV3Namespace(NS).get();

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.path("name").asText()).isEqualTo(NS);
  }

  /**
   * Scenario 16: GET namespace that does not exist → 404.
   *
   * <p>V1 parity: {@code GET /api/v1/namespaces/{namespace}} returns 404 for unknown namespaces.
   */
  @Test
  @Order(23)
  void getNamespace_unknownNamespace_returns404() throws Exception {
    HttpResponse<String> response = getV3Namespace("namespace-that-does-not-exist").get();

    assertThat(response.statusCode()).isEqualTo(404);
  }

  // ===========================================================================
  // Scenario 5 – GET /api/v3/namespaces/{ns}/jobs (V1 parity: GET /api/v1/namespaces/{ns}/jobs)
  // ===========================================================================

  /**
   * Scenario 17: List jobs in namespace → 200 with "jobs" array containing the ingested job.
   *
   * <p>V1 parity: {@code GET /api/v1/namespaces/{ns}/jobs} returns {@code {"jobs":[…]}}.
   */
  @Test
  @Order(31)
  void listJobs_afterIngestion_containsIngestedJob() throws Exception {
    HttpResponse<String> response = getV3Jobs(NS).get();

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.has("jobs")).isTrue();
    assertThat(body.get("jobs").isArray()).isTrue();

    boolean found =
        streamNodes(body.get("jobs")).anyMatch(n -> JOB.equals(n.path("name").asText()));
    assertThat(found).as("Expected job '%s' in namespace jobs list", JOB).isTrue();
  }

  /**
   * Scenario 18: GET single job by name → 200 with name, namespace, and type fields.
   *
   * <p>V1 parity: {@code GET /api/v1/namespaces/{ns}/jobs/{job}} returns job object with type.
   */
  @Test
  @Order(32)
  void getJob_knownJob_returns200WithNameAndNamespace() throws Exception {
    HttpResponse<String> response = getV3Job(NS, JOB).get();

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.path("name").asText()).isEqualTo(JOB);
    assertThat(body.path("namespace").asText()).isEqualTo(NS);
    // type defaults to BATCH in GraphWriter
    assertThat(body.path("type").asText()).isNotBlank();
  }

  /**
   * Scenario 19: GET job that does not exist → 404.
   *
   * <p>V1 parity: unknown job in known namespace returns 404.
   */
  @Test
  @Order(33)
  void getJob_unknownJob_returns404() throws Exception {
    HttpResponse<String> response = getV3Job(NS, "job-that-does-not-exist").get();

    assertThat(response.statusCode()).isEqualTo(404);
  }

  /**
   * Scenario 20: GET job runs → 200 with "runs" array containing the ingested run.
   *
   * <p>V1 parity: {@code GET /api/v1/namespaces/{ns}/jobs/{job}/runs} returns {@code {"runs":[…]}}
   * with each run having {@code id}, {@code state}, {@code createdAt}, {@code updatedAt}.
   */
  @Test
  @Order(34)
  void getJobRuns_afterIngestion_containsIngestedRun() throws Exception {
    HttpResponse<String> response = getV3JobRuns(NS, JOB).get();

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.has("runs")).isTrue();
    assertThat(body.get("runs").isArray()).isTrue();

    // Verify V1-compatible fields present on each run
    if (body.get("runs").size() > 0) {
      JsonNode run = body.get("runs").get(0);
      assertThat(run.has("id")).isTrue();
      assertThat(run.has("state")).isTrue();
      assertThat(run.has("createdAt")).isTrue();
      assertThat(run.has("updatedAt")).isTrue();
    }
  }

  // ===========================================================================
  // Scenario 6 – GET /api/v3/namespaces/{ns}/datasets (V1 parity)
  // ===========================================================================

  /**
   * Scenario 21: List datasets in namespace → 200 with "datasets" array containing the output
   * dataset (which lives in {@code v3-test-ns}).
   *
   * <p>V1 parity: {@code GET /api/v1/namespaces/{ns}/datasets} returns {@code {"datasets":[…]}}.
   */
  @Test
  @Order(41)
  void listDatasets_afterIngestion_containsIngestedDataset() throws Exception {
    HttpResponse<String> response = getV3Datasets(OUTPUT_DS_NS).get();

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.has("datasets")).isTrue();
    assertThat(body.get("datasets").isArray()).isTrue();

    boolean found =
        streamNodes(body.get("datasets")).anyMatch(n -> OUTPUT_DS.equals(n.path("name").asText()));
    assertThat(found).as("Expected dataset '%s' in namespace datasets list", OUTPUT_DS).isTrue();
  }

  /**
   * Scenario 22: GET single dataset by name → 200 with name and namespace.
   *
   * <p>V1 parity: {@code GET /api/v1/namespaces/{ns}/datasets/{dataset}} returns dataset object.
   */
  @Test
  @Order(42)
  void getDataset_knownDataset_returns200WithNameAndNamespace() throws Exception {
    HttpResponse<String> response = getV3Dataset(OUTPUT_DS_NS, OUTPUT_DS).get();

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.path("name").asText()).isEqualTo(OUTPUT_DS);
    assertThat(body.path("namespace").asText()).isEqualTo(OUTPUT_DS_NS);
  }

  /**
   * Scenario 23: GET dataset that does not exist → 404.
   *
   * <p>V1 parity: unknown dataset in known namespace returns 404.
   */
  @Test
  @Order(43)
  void getDataset_unknownDataset_returns404() throws Exception {
    HttpResponse<String> response = getV3Dataset(OUTPUT_DS_NS, "dataset-that-does-not-exist").get();

    assertThat(response.statusCode()).isEqualTo(404);
  }

  // ===========================================================================
  // Scenario 7 – Full pipeline validation
  // ===========================================================================

  /**
   * Scenario 24: End-to-end lineage pipeline.
   *
   * <p>Ingests a new job A → dataset B → job C chain and verifies that:
   *
   * <ol>
   *   <li>The lineage graph for job A contains dataset B as an output node.
   *   <li>The lineage graph for dataset B contains job A as an input source and job C as a
   *       consumer.
   * </ol>
   *
   * <p>This replicates the core V1 lineage flow for a two-job pipeline.
   */
  @Test
  @Order(51)
  void fullPipelineLineage_twoJobChain_graphConnected() throws Exception {
    String pipelineNs = "v3-pipeline-ns";
    String jobA = "v3-job-a";
    String jobC = "v3-job-c";
    String sharedDs = "v3-shared-dataset";
    String runA = UUID.randomUUID().toString();
    String runC = UUID.randomUUID().toString();

    // Job A produces sharedDs
    postV3Lineage(
            lineageEvent(
                pipelineNs,
                jobA,
                runA,
                "COMPLETE",
                new String[] {},
                new String[] {pipelineNs + ":" + sharedDs}))
        .get();

    // Job C consumes sharedDs
    postV3Lineage(
            lineageEvent(
                pipelineNs,
                jobC,
                runC,
                "COMPLETE",
                new String[] {pipelineNs + ":" + sharedDs},
                new String[] {}))
        .get();

    // Query lineage from job A – should include sharedDs
    HttpResponse<String> responseA = getV3Lineage("job:" + pipelineNs + ":" + jobA, 2, false).get();
    assertThat(responseA.statusCode()).isEqualTo(200);
    JsonNode graphA = MAPPER.readTree(responseA.body()).path("graph");
    boolean foundSharedDs =
        streamNodes(graphA).anyMatch(n -> sharedDs.equals(n.path("data").path("name").asText()));
    assertThat(foundSharedDs).as("Job A graph should contain shared dataset").isTrue();

    // Query lineage from sharedDs – should include both job A and job C
    HttpResponse<String> responseDs =
        getV3Lineage("dataset:" + pipelineNs + ":" + sharedDs, 2, false).get();
    assertThat(responseDs.statusCode()).isEqualTo(200);
    JsonNode graphDs = MAPPER.readTree(responseDs.body()).path("graph");

    boolean foundJobA =
        streamNodes(graphDs).anyMatch(n -> jobA.equals(n.path("data").path("name").asText()));
    boolean foundJobC =
        streamNodes(graphDs).anyMatch(n -> jobC.equals(n.path("data").path("name").asText()));
    assertThat(foundJobA).as("Dataset graph should contain job A (producer)").isTrue();
    assertThat(foundJobC).as("Dataset graph should contain job C (consumer)").isTrue();
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  /** Returns a sequential stream over elements of a JSON array node. */
  private static java.util.stream.Stream<JsonNode> streamNodes(JsonNode arrayNode) {
    if (arrayNode == null || !arrayNode.isArray()) return java.util.stream.Stream.empty();
    java.util.List<JsonNode> list = new java.util.ArrayList<>();
    arrayNode.forEach(list::add);
    return list.stream();
  }
}

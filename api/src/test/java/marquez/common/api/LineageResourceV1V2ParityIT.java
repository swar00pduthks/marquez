/*
 * Copyright 2018-2026 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import marquez.BaseIntegrationTest;
import marquez.MarquezApp;
import marquez.api.JdbiUtils;
import marquez.service.DenormalizedLineageService;
import marquez.service.PartitionManagementService;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Parity regression tests: V1 and V2 lineage endpoints must agree on edge cases, particularly:
 *
 * <ul>
 *   <li>URL-encoded OpenLineage-style namespaces (https://, postgres://, s3://)
 *   <li>Invalid / missing nodeId handling
 * </ul>
 *
 * <p>The main happy-path parity is covered in {@link LineageResourceV2IntegrationTest}. This suite
 * covers the URL-encoding + error-shape gaps only.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class LineageResourceV1V2ParityIT extends BaseIntegrationTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @AfterEach
  public void tearDown() {
    JdbiUtils.cleanDatabase(MarquezApp.getJdbiInstanceForTesting());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void populateDenormalized(UUID runId) {
    Jdbi jdbi = MarquezApp.getJdbiInstanceForTesting();
    DenormalizedLineageService svc =
        new DenormalizedLineageService(jdbi, new PartitionManagementService(jdbi, 10, 12));
    svc.populateAllDenormalizedEntities();
    svc.populateLineageForRun(runId);
  }

  private String buildCompleteEvent(
      UUID runId, String namespace, String jobName, String inputDataset, String outputDataset) {
    return String.format(
        "{\"eventType\":\"COMPLETE\",\"eventTime\":\"%s\","
            + "\"run\":{\"runId\":\"%s\"},"
            + "\"job\":{\"namespace\":\"%s\",\"name\":\"%s\"},"
            + "\"inputs\":[{\"namespace\":\"%s\",\"name\":\"%s\"}],"
            + "\"outputs\":[{\"namespace\":\"%s\",\"name\":\"%s\"}],"
            + "\"producer\":\"https://github.com/OpenLineage/OpenLineage/blob/v1-0-0/client\","
            + "\"schemaURL\":\"https://openlineage.io/spec/1-0-1/OpenLineage.json#/definitions/RunEvent\"}",
        Instant.now(),
        runId,
        namespace,
        jobName,
        namespace,
        inputDataset,
        namespace,
        outputDataset);
  }

  private HttpResponse<String> fetchLineage(String apiPrefix, String nodeId, int depth)
      throws Exception {
    return fetchLineageFull(apiPrefix, nodeId, depth, false, "");
  }

  private HttpResponse<String> fetchLineageFull(
      String apiPrefix, String nodeId, int depth, boolean aggregateToParentRun, String includeFacet)
      throws Exception {
    String encoded = URLEncoder.encode(nodeId, StandardCharsets.UTF_8);
    StringBuilder q =
        new StringBuilder(apiPrefix)
            .append("/lineage?nodeId=")
            .append(encoded)
            .append("&depth=")
            .append(depth)
            .append("&aggregateToParentRun=")
            .append(aggregateToParentRun);
    if (includeFacet != null && !includeFacet.isBlank()) {
      q.append("&includeFacets=").append(URLEncoder.encode(includeFacet, StandardCharsets.UTF_8));
    }
    URI uri = URI.create(baseUrl + q.toString());
    return http2.send(
        HttpRequest.newBuilder().uri(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode normalizeJson(JsonNode node) {
    if (node == null || node.isNull() || node.isValueNode()) return node;
    if (node.isArray()) {
      ArrayNode out = mapper.createArrayNode();
      List<JsonNode> children = new ArrayList<>();
      node.forEach(c -> children.add(normalizeJson(c)));
      children.sort(Comparator.comparing(JsonNode::toString));
      children.forEach(out::add);
      return out;
    }
    ObjectNode out = mapper.createObjectNode();
    List<String> fields = new ArrayList<>();
    node.fieldNames().forEachRemaining(fields::add);
    fields.sort(String::compareTo);
    fields.forEach(f -> out.set(f, normalizeJson(node.get(f))));
    return out;
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  public void testLineage_httpsUriNamespace_v1AndV2_bothSucceedWithEncodedNodeId()
      throws Exception {
    String uriNs = "https://github.com/acme/lineage-ns";
    client.createNamespace(
        uriNs, marquez.client.models.NamespaceMeta.builder().ownerName(OWNER_NAME).build());

    UUID runId = UUID.randomUUID();
    HttpResponse<String> ingest =
        sendLineage(buildCompleteEvent(runId, uriNs, "uri_ns_job", "uri_ns_in", "uri_ns_out"))
            .join();
    assertThat(ingest.statusCode()).isEqualTo(201);

    populateDenormalized(runId);

    // Both V1 and V2 must accept a URL-encoded https:// namespace inside nodeId.
    String nodeId = "job:" + uriNs + ":uri_ns_job";
    HttpResponse<String> v1 = fetchLineage("/api/v1", nodeId, 2);
    HttpResponse<String> v2 = fetchLineage("/api/v2", nodeId, 2);
    assertThat(v1.statusCode()).as("V1 lineage for https:// ns").isEqualTo(200);
    assertThat(v2.statusCode()).as("V2 lineage for https:// ns").isEqualTo(200);

    // Structural parity — V1 and V2 must return equivalent graphs
    assertThat(normalizeJson(mapper.readTree(v2.body())))
        .isEqualTo(normalizeJson(mapper.readTree(v1.body())));

    // The URI namespace must appear verbatim in the node id
    assertThat(mapper.readTree(v2.body()).path("graph").findValuesAsText("id")).contains(nodeId);
  }

  @Test
  public void testLineage_missingNode_v1AndV2_bothReturn404() throws Exception {
    HttpResponse<String> v1 = fetchLineage("/api/v1", "job:never_existed_ns:never_existed_job", 2);
    HttpResponse<String> v2 = fetchLineage("/api/v2", "job:never_existed_ns:never_existed_job", 2);
    assertThat(v1.statusCode()).as("V1 missing-node status").isEqualTo(404);
    assertThat(v2.statusCode()).as("V2 missing-node status").isEqualTo(404);
  }

  // ---------------------------------------------------------------------------
  // Tests — run-node lineage, aggregateToParentRun, includeFacets
  // ---------------------------------------------------------------------------

  @Test
  public void testLineage_runNode_v1AndV2_returnSameGraph() throws Exception {
    createNamespace(NAMESPACE_NAME);
    UUID runId = UUID.randomUUID();
    assertThat(
            sendLineage(
                    buildCompleteEvent(
                        runId, NAMESPACE_NAME, "run_parity_job", "run_parity_in", "run_parity_out"))
                .join()
                .statusCode())
        .isEqualTo(201);
    populateDenormalized(runId);

    String nodeId = "run:" + runId;
    HttpResponse<String> v1 = fetchLineageFull("/api/v1", nodeId, 2, false, "");
    HttpResponse<String> v2 = fetchLineageFull("/api/v2", nodeId, 2, false, "");
    assertThat(v1.statusCode()).as("V1 run-node status").isEqualTo(200);
    assertThat(v2.statusCode()).as("V2 run-node status").isEqualTo(200);

    assertThat(normalizeJson(mapper.readTree(v2.body())))
        .as("V1 and V2 graphs must be structurally identical for run: node")
        .isEqualTo(normalizeJson(mapper.readTree(v1.body())));

    // The run node id must be present in the graph
    assertThat(mapper.readTree(v2.body()).path("graph").findValuesAsText("id")).contains(nodeId);
  }

  @Test
  public void testLineage_aggregateToParentRun_v1AndV2_parity() throws Exception {
    createNamespace(NAMESPACE_NAME);
    UUID parentRunId = UUID.randomUUID();
    UUID childRunId = UUID.randomUUID();

    assertThat(
            sendLineage(
                    buildCompleteEvent(
                        parentRunId,
                        NAMESPACE_NAME,
                        "parent_agg_job",
                        "agg_seed_input",
                        "agg_shared_output"))
                .join()
                .statusCode())
        .isEqualTo(201);
    assertThat(
            sendLineage(
                    buildCompleteEvent(
                        childRunId,
                        NAMESPACE_NAME,
                        "child_agg_job",
                        "agg_shared_output",
                        "agg_child_output"))
                .join()
                .statusCode())
        .isEqualTo(201);

    // Wire child → parent linkage
    MarquezApp.getJdbiInstanceForTesting()
        .useHandle(
            h ->
                h.execute(
                    "UPDATE runs SET parent_run_uuid = ? WHERE uuid = ?", parentRunId, childRunId));

    populateDenormalized(parentRunId);
    populateDenormalized(childRunId);

    String nodeId = "run:" + parentRunId;
    HttpResponse<String> v1 = fetchLineageFull("/api/v1", nodeId, 2, true, "");
    HttpResponse<String> v2 = fetchLineageFull("/api/v2", nodeId, 2, true, "");
    assertThat(v1.statusCode()).as("V1 aggregateToParentRun status").isEqualTo(200);
    assertThat(v2.statusCode()).as("V2 aggregateToParentRun status").isEqualTo(200);

    // With aggregateToParentRun=true, the parent graph must contain the child's output
    // dataset. Graph node ids for datasets include a "#<versionUUID>" suffix, so assert
    // that SOME node id starts with the expected dataset prefix.
    String expectedChildOutputPrefix = "dataset:" + NAMESPACE_NAME + ":agg_child_output";
    assertThat(mapper.readTree(v2.body()).path("graph").findValuesAsText("id"))
        .as("V2 aggregated graph must contain child's output dataset (any version)")
        .anyMatch(id -> id.startsWith(expectedChildOutputPrefix));

    // Full structural parity with V1 under aggregateToParentRun=true
    assertThat(normalizeJson(mapper.readTree(v2.body())))
        .as("aggregateToParentRun parity")
        .isEqualTo(normalizeJson(mapper.readTree(v1.body())));
  }

  @Test
  public void testLineage_includeFacets_v1AndV2_parity() throws Exception {
    createNamespace(NAMESPACE_NAME);
    UUID runId = UUID.randomUUID();
    assertThat(
            sendLineage(
                    buildCompleteEvent(
                        runId,
                        NAMESPACE_NAME,
                        "facet_parity_job",
                        "facet_parity_in",
                        "facet_parity_out"))
                .join()
                .statusCode())
        .isEqualTo(201);
    populateDenormalized(runId);

    String nodeId = "job:" + NAMESPACE_NAME + ":facet_parity_job";

    // Case 1: includeFacets=spark — narrow filter
    HttpResponse<String> v1Spark = fetchLineageFull("/api/v1", nodeId, 2, false, "spark");
    HttpResponse<String> v2Spark = fetchLineageFull("/api/v2", nodeId, 2, false, "spark");
    assertThat(v1Spark.statusCode()).isEqualTo(200);
    assertThat(v2Spark.statusCode()).isEqualTo(200);
    assertThat(normalizeJson(mapper.readTree(v2Spark.body())))
        .as("includeFacets=spark parity")
        .isEqualTo(normalizeJson(mapper.readTree(v1Spark.body())));

    // Case 2: no includeFacets (default) — different response but still V1↔V2 parity
    HttpResponse<String> v1Default = fetchLineageFull("/api/v1", nodeId, 2, false, "");
    HttpResponse<String> v2Default = fetchLineageFull("/api/v2", nodeId, 2, false, "");
    assertThat(v1Default.statusCode()).isEqualTo(200);
    assertThat(v2Default.statusCode()).isEqualTo(200);
    assertThat(normalizeJson(mapper.readTree(v2Default.body())))
        .as("default-facets parity")
        .isEqualTo(normalizeJson(mapper.readTree(v1Default.body())));
  }
}

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
 * Parity regression tests: V1 and V2 column-lineage endpoints must agree.
 *
 * <p>V1: /api/v1/column-lineage, V2: /api/v2/column-lineage. Both take a nodeId query param
 * (dataset or datasetField) and return a Lineage graph. This test guards against drift and
 * URL-encoding regressions for https:// namespaces.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class ColumnLineageResourceV1V2ParityIT extends BaseIntegrationTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @AfterEach
  public void tearDown() {
    JdbiUtils.cleanDatabase(MarquezApp.getJdbiInstanceForTesting());
  }

  private void populateDenormalized(UUID runId) {
    Jdbi jdbi = MarquezApp.getJdbiInstanceForTesting();
    DenormalizedLineageService svc =
        new DenormalizedLineageService(jdbi, new PartitionManagementService(jdbi, 10, 12));
    svc.populateAllDenormalizedEntities();
    svc.populateLineageForRun(runId);
  }

  private String buildEventWithSchemaAndColumnLineage(
      UUID runId, String ns, String jobName, String inputDs, String outputDs) {
    return String.format(
        "{\"eventType\":\"COMPLETE\",\"eventTime\":\"%s\","
            + "\"run\":{\"runId\":\"%s\"},"
            + "\"job\":{\"namespace\":\"%s\",\"name\":\"%s\"},"
            + "\"inputs\":[{\"namespace\":\"%s\",\"name\":\"%s\","
            + "  \"facets\":{\"schema\":{\"_producer\":\"p\",\"_schemaURL\":\"s\","
            + "    \"fields\":[{\"name\":\"col_a\",\"type\":\"STRING\"},"
            + "               {\"name\":\"col_b\",\"type\":\"INT\"}]}}}],"
            + "\"outputs\":[{\"namespace\":\"%s\",\"name\":\"%s\","
            + "  \"facets\":{\"schema\":{\"_producer\":\"p\",\"_schemaURL\":\"s\","
            + "    \"fields\":[{\"name\":\"out_col\",\"type\":\"STRING\"}]},"
            + "    \"columnLineage\":{\"_producer\":\"p\",\"_schemaURL\":\"s\","
            + "      \"fields\":{\"out_col\":{\"inputFields\":["
            + "        {\"namespace\":\"%s\",\"name\":\"%s\",\"field\":\"col_a\"}],"
            + "        \"transformationDescription\":\"passthrough\","
            + "        \"transformationType\":\"IDENTITY\"}}}}}],"
            + "\"producer\":\"https://github.com/OpenLineage/OpenLineage/blob/v1-0-0/client\","
            + "\"schemaURL\":\"https://openlineage.io/spec/1-0-1/OpenLineage.json#/definitions/RunEvent\"}",
        Instant.now(), runId, ns, jobName, ns, inputDs, ns, outputDs, ns, inputDs);
  }

  private HttpResponse<String> fetchColumnLineage(String apiPrefix, String nodeId)
      throws Exception {
    String encoded = URLEncoder.encode(nodeId, StandardCharsets.UTF_8);
    URI uri = URI.create(baseUrl + apiPrefix + "/column-lineage?nodeId=" + encoded);
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

  @Test
  public void testColumnLineage_byDataset_v1AndV2_returnSameGraph() throws Exception {
    createNamespace(NAMESPACE_NAME);
    UUID runId = UUID.randomUUID();
    HttpResponse<String> ingest =
        sendLineage(
                buildEventWithSchemaAndColumnLineage(
                    runId, NAMESPACE_NAME, "cl_parity_job", "cl_parity_in", "cl_parity_out"))
            .join();
    assertThat(ingest.statusCode()).isEqualTo(201);
    populateDenormalized(runId);

    String nodeId = "dataset:" + NAMESPACE_NAME + ":cl_parity_out";
    HttpResponse<String> v1 = fetchColumnLineage("/api/v1", nodeId);
    HttpResponse<String> v2 = fetchColumnLineage("/api/v2", nodeId);
    assertThat(v1.statusCode()).isEqualTo(200);
    assertThat(v2.statusCode()).isEqualTo(200);

    assertThat(normalizeJson(mapper.readTree(v2.body())))
        .as("V1 and V2 column-lineage graphs must be structurally identical")
        .isEqualTo(normalizeJson(mapper.readTree(v1.body())));
  }

  @Test
  public void testColumnLineage_byDatasetField_v1AndV2_parity() throws Exception {
    createNamespace(NAMESPACE_NAME);
    UUID runId = UUID.randomUUID();
    assertThat(
            sendLineage(
                    buildEventWithSchemaAndColumnLineage(
                        runId, NAMESPACE_NAME, "cl_field_job", "cl_field_in", "cl_field_out"))
                .join()
                .statusCode())
        .isEqualTo(201);
    populateDenormalized(runId);

    String nodeId = "datasetField:" + NAMESPACE_NAME + ":cl_field_out:out_col";
    HttpResponse<String> v1 = fetchColumnLineage("/api/v1", nodeId);
    HttpResponse<String> v2 = fetchColumnLineage("/api/v2", nodeId);
    assertThat(v1.statusCode()).isEqualTo(200);
    assertThat(v2.statusCode()).isEqualTo(200);
    assertThat(normalizeJson(mapper.readTree(v2.body())))
        .isEqualTo(normalizeJson(mapper.readTree(v1.body())));
  }

  @Test
  public void testColumnLineage_httpsUriNamespace_v1AndV2_parity() throws Exception {
    String uriNs = "https://github.com/acme/column-lineage-repo";
    client.createNamespace(
        uriNs, marquez.client.models.NamespaceMeta.builder().ownerName(OWNER_NAME).build());

    UUID runId = UUID.randomUUID();
    assertThat(
            sendLineage(
                    buildEventWithSchemaAndColumnLineage(
                        runId, uriNs, "uri_cl_job", "uri_cl_in", "uri_cl_out"))
                .join()
                .statusCode())
        .isEqualTo(201);
    populateDenormalized(runId);

    String nodeId = "dataset:" + uriNs + ":uri_cl_out";
    HttpResponse<String> v1 = fetchColumnLineage("/api/v1", nodeId);
    HttpResponse<String> v2 = fetchColumnLineage("/api/v2", nodeId);
    assertThat(v1.statusCode()).as("V1 column-lineage https:// ns").isEqualTo(200);
    assertThat(v2.statusCode()).as("V2 column-lineage https:// ns").isEqualTo(200);
    assertThat(normalizeJson(mapper.readTree(v2.body())))
        .isEqualTo(normalizeJson(mapper.readTree(v1.body())));
  }

  @Test
  public void testColumnLineage_missingNodeId_v2Returns400() throws Exception {
    URI uri = URI.create(baseUrl + "/api/v2/column-lineage");
    HttpResponse<String> resp =
        http2.send(
            HttpRequest.newBuilder().uri(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).as("missing nodeId must 400").isEqualTo(400);
  }

  @Test
  public void testColumnLineage_missingNode_v2Returns404() throws Exception {
    HttpResponse<String> resp =
        fetchColumnLineage("/api/v2", "dataset:never_existed_ns:never_existed_dataset");
    assertThat(resp.statusCode()).isEqualTo(404);
  }
}

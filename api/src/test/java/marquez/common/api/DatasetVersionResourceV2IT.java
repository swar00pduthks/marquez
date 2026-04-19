/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import marquez.BaseIntegrationTest;
import marquez.MarquezApp;
import marquez.api.JdbiUtils;
import marquez.service.DenormalizedLineageService;
import marquez.service.PartitionManagementService;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the V2 Dataset Versions endpoint.
 *
 * <p>The V2 versions endpoint ({@code GET /api/v2/namespaces/{ns}/datasets/{ds}/versions}) is
 * backed by {@code dataset_versions_view_v2}, which joins {@code dataset_version_denormalized} with
 * {@code dataset_versions} (normalized). These tests verify:
 *
 * <ul>
 *   <li>Versions created via the V1 REST API are visible in V2 after denorm population
 *   <li>Versions created via an OpenLineage event (with a run) carry {@code createdByRun}
 *   <li>V1 and V2 version lists agree on version UUIDs
 *   <li>Pagination works correctly
 *   <li>Error cases (missing namespace / dataset) return 404
 * </ul>
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class DatasetVersionResourceV2IT extends BaseIntegrationTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @BeforeEach
  public void setup() {
    createNamespace(NAMESPACE_NAME);
    createSource(DB_TABLE_SOURCE_NAME);
  }

  @AfterEach
  public void tearDown() {
    JdbiUtils.cleanDatabase(MarquezApp.getJdbiInstanceForTesting());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void populateDenormalized() {
    Jdbi jdbi = MarquezApp.getJdbiInstanceForTesting();
    new DenormalizedLineageService(jdbi, new PartitionManagementService(jdbi, 10, 12))
        .populateAllDenormalizedEntities();
  }

  private String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private HttpResponse<String> httpGet(String path) throws Exception {
    return http2.send(
        HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> httpPost(String path, String body) throws Exception {
    return http2.send(
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private List<JsonNode> extractList(String body, String arrayKey) throws Exception {
    JsonNode root = MAPPER.readTree(body);
    JsonNode arrayNode = root.isArray() ? root : root.path(arrayKey);
    List<JsonNode> result = new ArrayList<>();
    arrayNode.forEach(result::add);
    return result;
  }

  /**
   * Posts a minimal OpenLineage COMPLETE event that produces {@code datasetName} in the given
   * namespace via a run with the specified UUID.
   */
  private void postOpenLineageComplete(
      String runUuid, String jobName, String namespaceName, String datasetName) throws Exception {
    String body =
        String.format(
            "{"
                + "\"eventType\":\"COMPLETE\","
                + "\"eventTime\":\"2024-01-01T00:00:00Z\","
                + "\"producer\":\"https://github.com/MarquezProject/marquez\","
                + "\"schemaURL\":\"https://openlineage.io/spec/1-0-5/OpenLineage.json#/$defs/RunEvent\","
                + "\"run\":{\"runId\":\"%s\",\"facets\":{}},"
                + "\"job\":{\"namespace\":\"%s\",\"name\":\"%s\",\"facets\":{}},"
                + "\"inputs\":[],"
                + "\"outputs\":[{\"namespace\":\"%s\",\"name\":\"%s\",\"facets\":{}}]"
                + "}",
            runUuid, namespaceName, jobName, namespaceName, datasetName);
    HttpResponse<String> resp = httpPost("/api/v1/lineage", body);
    assertThat(resp.statusCode()).as("lineage event status").isEqualTo(201);
  }

  /**
   * Posts an OpenLineage COMPLETE event with a distinct field schema, forcing a unique version UUID
   * (version is fingerprinted from content — different fields → different version).
   */
  private void postOpenLineageCompleteWithField(
      String runUuid, String jobName, String namespaceName, String datasetName, String fieldName)
      throws Exception {
    String body =
        String.format(
            "{"
                + "\"eventType\":\"COMPLETE\","
                + "\"eventTime\":\"2024-01-01T00:00:00Z\","
                + "\"producer\":\"https://github.com/MarquezProject/marquez\","
                + "\"schemaURL\":\"https://openlineage.io/spec/1-0-5/OpenLineage.json#/$defs/RunEvent\","
                + "\"run\":{\"runId\":\"%s\",\"facets\":{}},"
                + "\"job\":{\"namespace\":\"%s\",\"name\":\"%s\",\"facets\":{}},"
                + "\"inputs\":[],"
                + "\"outputs\":[{"
                + "  \"namespace\":\"%s\","
                + "  \"name\":\"%s\","
                + "  \"facets\":{"
                + "    \"schema\":{"
                + "      \"_producer\":\"test\","
                + "      \"_schemaURL\":\"https://openlineage.io/spec/facets/1-0-0/SchemaDatasetFacet.json\","
                + "      \"fields\":[{\"name\":\"%s\",\"type\":\"VARCHAR\"}]"
                + "    }"
                + "  }"
                + "}]"
                + "}",
            runUuid, namespaceName, jobName, namespaceName, datasetName, fieldName);
    HttpResponse<String> resp = httpPost("/api/v1/lineage", body);
    assertThat(resp.statusCode()).as("lineage event status").isEqualTo(201);
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  public void testListVersions_v2_notEmpty_afterDatasetCreation() throws Exception {
    // Creating a dataset via V1 REST API creates exactly one dataset version
    client.createDataset(NAMESPACE_NAME, "versions_v2_ds", DB_TABLE_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    HttpResponse<String> resp =
        httpGet("/api/v2/namespaces/" + ns + "/datasets/versions_v2_ds/versions");
    assertThat(resp.statusCode()).as("V2 versions status").isEqualTo(200);

    List<JsonNode> versions = extractList(resp.body(), "versions");
    assertThat(versions).as("at least one version exists").isNotEmpty();
  }

  @Test
  public void testListVersions_v1AndV2_agreeOnVersionUuids() throws Exception {
    client.createDataset(NAMESPACE_NAME, "version_parity_ds", DB_TABLE_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    // V1 versions
    HttpResponse<String> v1Resp =
        httpGet("/api/v1/namespaces/" + ns + "/datasets/version_parity_ds/versions");
    assertThat(v1Resp.statusCode()).as("V1 versions status").isEqualTo(200);
    Set<String> v1Uuids =
        extractList(v1Resp.body(), "versions").stream()
            .map(n -> n.path("version").asText())
            .collect(Collectors.toSet());

    // V2 versions
    HttpResponse<String> v2Resp =
        httpGet("/api/v2/namespaces/" + ns + "/datasets/version_parity_ds/versions");
    assertThat(v2Resp.statusCode()).as("V2 versions status").isEqualTo(200);
    Set<String> v2Uuids =
        extractList(v2Resp.body(), "versions").stream()
            .map(n -> n.path("version").asText())
            .collect(Collectors.toSet());

    // Every version UUID in V1 must appear in V2
    assertThat(v2Uuids).containsAll(v1Uuids);
  }

  @Test
  public void testGetVersion_v2_byVersionUuid() throws Exception {
    client.createDataset(NAMESPACE_NAME, "get_version_ds", DB_TABLE_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    // First list to retrieve the version UUID
    List<JsonNode> v2Versions =
        extractList(
            httpGet("/api/v2/namespaces/" + ns + "/datasets/get_version_ds/versions").body(),
            "versions");
    assertThat(v2Versions).isNotEmpty();

    String versionUuid = v2Versions.get(0).path("version").asText();
    assertThat(versionUuid).isNotBlank();

    // GET by version UUID
    HttpResponse<String> byUuidResp =
        httpGet("/api/v2/namespaces/" + ns + "/datasets/get_version_ds/versions/" + versionUuid);
    assertThat(byUuidResp.statusCode()).as("GET by version UUID status").isEqualTo(200);

    JsonNode version = MAPPER.readTree(byUuidResp.body());
    assertThat(version.path("version").asText()).isEqualTo(versionUuid);
  }

  @Test
  public void testVersion_v2_hasCoreFieldsPopulated() throws Exception {
    client.createDataset(NAMESPACE_NAME, "version_fields_ds", DB_TABLE_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    List<JsonNode> versions =
        extractList(
            httpGet("/api/v2/namespaces/" + ns + "/datasets/version_fields_ds/versions").body(),
            "versions");
    assertThat(versions).isNotEmpty();

    JsonNode v = versions.get(0);
    // Core fields from dataset_versions_view_v2 must be populated
    assertThat(v.path("name").asText()).as("name").isNotBlank();
    assertThat(v.path("version").asText()).as("version UUID").isNotBlank();
    assertThat(v.path("createdAt").asText()).as("createdAt").isNotBlank();
    assertThat(v.path("sourceName").asText()).as("sourceName").isNotBlank();
    assertThat(v.path("physicalName").asText()).as("physicalName").isNotBlank();
  }

  @Test
  public void testVersion_v2_createdByRun_populatedFromOpenLineageEvent() throws Exception {
    String runUuid = UUID.randomUUID().toString();
    // Send an OpenLineage COMPLETE event — this creates a dataset version linked to a run
    postOpenLineageComplete(runUuid, "version_run_job", NAMESPACE_NAME, "run_linked_ds");
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    HttpResponse<String> resp =
        httpGet("/api/v2/namespaces/" + ns + "/datasets/run_linked_ds/versions");
    assertThat(resp.statusCode()).as("V2 versions status").isEqualTo(200);

    List<JsonNode> versions = extractList(resp.body(), "versions");
    assertThat(versions).isNotEmpty();

    // The version produced by the run should carry createdByRun
    boolean anyHasRun =
        versions.stream()
            .anyMatch(
                v -> !v.path("createdByRun").isMissingNode() && !v.path("createdByRun").isNull());
    assertThat(anyHasRun).as("at least one version has createdByRun").isTrue();
  }

  @Test
  public void testListVersions_v2_paginationWorks() throws Exception {
    // Each event includes a distinct field schema — Marquez fingerprints the version UUID from
    // schema content, so different fields → different version UUIDs → 4 unique versions.
    for (int i = 0; i < 4; i++) {
      String runUuid = UUID.randomUUID().toString();
      postOpenLineageCompleteWithField(
          runUuid,
          "pagination_version_job_" + i,
          NAMESPACE_NAME,
          "pagination_versions_ds",
          "field_col_" + i);
    }
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    String base = "/api/v2/namespaces/" + ns + "/datasets/pagination_versions_ds/versions";

    // Request page1 and page2 — each should have 2 items with no overlap
    HttpResponse<String> page1Resp = httpGet(base + "?limit=2&offset=0");
    HttpResponse<String> page2Resp = httpGet(base + "?limit=2&offset=2");

    assertThat(page1Resp.statusCode()).as("page1 status").isEqualTo(200);
    assertThat(page2Resp.statusCode()).as("page2 status").isEqualTo(200);

    List<JsonNode> page1 = extractList(page1Resp.body(), "versions");
    List<JsonNode> page2 = extractList(page2Resp.body(), "versions");

    assertThat(page1).as("page1 has items").isNotEmpty();
    assertThat(page2).as("page2 has items").isNotEmpty();

    Set<String> page1Uuids =
        page1.stream().map(n -> n.path("version").asText()).collect(Collectors.toSet());
    Set<String> page2Uuids =
        page2.stream().map(n -> n.path("version").asText()).collect(Collectors.toSet());
    assertThat(page1Uuids).doesNotContainAnyElementsOf(page2Uuids);
  }

  @Test
  public void testListVersions_v2Returns404_namespaceNotFound() throws Exception {
    HttpResponse<String> resp =
        httpGet("/api/v2/namespaces/totally_missing_ns/datasets/some_ds/versions");
    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  public void testListVersions_v2Returns404_datasetNotFound() throws Exception {
    HttpResponse<String> resp =
        httpGet("/api/v2/namespaces/" + enc(NAMESPACE_NAME) + "/datasets/no_such_ds/versions");
    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  public void testGetVersion_v2Returns404_versionNotFound() throws Exception {
    client.createDataset(NAMESPACE_NAME, "get_version_404_ds", DB_TABLE_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    String nonExistentVersionUuid = UUID.randomUUID().toString();
    HttpResponse<String> resp =
        httpGet(
            "/api/v2/namespaces/"
                + ns
                + "/datasets/get_version_404_ds/versions/"
                + nonExistentVersionUuid);
    assertThat(resp.statusCode()).isEqualTo(404);
  }
}

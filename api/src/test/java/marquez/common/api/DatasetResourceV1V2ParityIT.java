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
 * Parity regression tests: V1 and V2 dataset endpoints must agree on the same data.
 *
 * <p>These tests guard against regressions introduced by the denormalized-table-backed V2 views
 * (datasets_view_v2). Each test creates canonical data via the V1 REST API, populates the
 * denormalized tables explicitly, then compares the V1 and V2 responses for correctness.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class DatasetResourceV1V2ParityIT extends BaseIntegrationTest {

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

  private HttpResponse<String> httpDelete(String path) throws Exception {
    return http2.send(
        HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Extract the named JSON array from a possibly-wrapped response body. Handles both {@code
   * {"datasets":[...], "totalCount":N}} and plain {@code [...]}.
   */
  private List<JsonNode> extractList(String body, String arrayKey) throws Exception {
    JsonNode root = MAPPER.readTree(body);
    JsonNode arrayNode = root.isArray() ? root : root.path(arrayKey);
    List<JsonNode> result = new ArrayList<>();
    arrayNode.forEach(result::add);
    return result;
  }

  private int extractTotalCount(String body) throws Exception {
    JsonNode root = MAPPER.readTree(body);
    return root.path("totalCount").asInt(-1);
  }

  // ---------------------------------------------------------------------------
  // Tests — list endpoint parity
  // ---------------------------------------------------------------------------

  @Test
  public void testListDatasets_v1AndV2_containSameDatasetNames() throws Exception {
    // Arrange: create three datasets via V1 client
    client.createDataset(NAMESPACE_NAME, "parity_ds_alpha", DB_TABLE_META);
    client.createDataset(NAMESPACE_NAME, "parity_ds_beta", DB_TABLE_META);
    client.createDataset(NAMESPACE_NAME, "parity_ds_gamma", DB_TABLE_META);

    // V1 response before denorm — serves normalized tables
    HttpResponse<String> v1Resp =
        httpGet("/api/v1/namespaces/" + enc(NAMESPACE_NAME) + "/datasets");
    assertThat(v1Resp.statusCode()).as("V1 list status").isEqualTo(200);
    Set<String> v1Names =
        extractList(v1Resp.body(), "datasets").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    // Populate denorm then query V2
    populateDenormalized();
    HttpResponse<String> v2Resp =
        httpGet("/api/v2/namespaces/" + enc(NAMESPACE_NAME) + "/datasets");
    assertThat(v2Resp.statusCode()).as("V2 list status").isEqualTo(200);
    Set<String> v2Names =
        extractList(v2Resp.body(), "datasets").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    // V2 must expose every dataset that V1 exposes
    assertThat(v2Names).containsAll(v1Names);
    assertThat(v2Names).contains("parity_ds_alpha", "parity_ds_beta", "parity_ds_gamma");
  }

  @Test
  public void testListDatasets_totalCount_v2_matchesActualListSize() throws Exception {
    for (int i = 0; i < 5; i++) {
      client.createDataset(NAMESPACE_NAME, "count_ds_" + i, DB_TABLE_META);
    }
    populateDenormalized();

    HttpResponse<String> resp =
        httpGet("/api/v2/namespaces/" + enc(NAMESPACE_NAME) + "/datasets?limit=100&offset=0");
    assertThat(resp.statusCode()).isEqualTo(200);

    List<JsonNode> datasets = extractList(resp.body(), "datasets");
    int totalCount = extractTotalCount(resp.body());

    assertThat(datasets).hasSizeGreaterThanOrEqualTo(5);
    // When limit >= total rows, totalCount must equal list size
    assertThat(totalCount).isEqualTo(datasets.size());
  }

  @Test
  public void testListDatasets_v2_paginationIsConsistent() throws Exception {
    // Create 8 datasets
    for (int i = 0; i < 8; i++) {
      client.createDataset(NAMESPACE_NAME, "page_ds_" + String.format("%02d", i), DB_TABLE_META);
    }
    populateDenormalized();

    String base = "/api/v2/namespaces/" + enc(NAMESPACE_NAME) + "/datasets";
    List<JsonNode> page1 = extractList(httpGet(base + "?limit=4&offset=0").body(), "datasets");
    List<JsonNode> page2 = extractList(httpGet(base + "?limit=4&offset=4").body(), "datasets");

    assertThat(page1).hasSize(4);
    assertThat(page2).hasSize(4);

    Set<String> page1Names =
        page1.stream().map(n -> n.path("name").asText()).collect(Collectors.toSet());
    Set<String> page2Names =
        page2.stream().map(n -> n.path("name").asText()).collect(Collectors.toSet());
    assertThat(page1Names).doesNotContainAnyElementsOf(page2Names);
  }

  // ---------------------------------------------------------------------------
  // Tests — get-by-name parity
  // ---------------------------------------------------------------------------

  @Test
  public void testGetDataset_v1AndV2_coreFieldsMustMatch() throws Exception {
    client.createDataset(NAMESPACE_NAME, "parity_get_ds", DB_TABLE_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    HttpResponse<String> v1Resp = httpGet("/api/v1/namespaces/" + ns + "/datasets/parity_get_ds");
    HttpResponse<String> v2Resp = httpGet("/api/v2/namespaces/" + ns + "/datasets/parity_get_ds");

    assertThat(v1Resp.statusCode()).as("V1 get status").isEqualTo(200);
    assertThat(v2Resp.statusCode()).as("V2 get status").isEqualTo(200);

    JsonNode v1 = MAPPER.readTree(v1Resp.body());
    JsonNode v2 = MAPPER.readTree(v2Resp.body());

    assertThat(v2.path("name").asText()).as("name parity").isEqualTo(v1.path("name").asText());
    assertThat(v2.path("type").asText()).as("type parity").isEqualTo(v1.path("type").asText());
    assertThat(v2.path("sourceName").asText())
        .as("sourceName parity")
        .isEqualTo(v1.path("sourceName").asText());
    assertThat(v2.path("physicalName").asText())
        .as("physicalName parity")
        .isEqualTo(v1.path("physicalName").asText());
    assertThat(v2.path("description").asText())
        .as("description parity")
        .isEqualTo(v1.path("description").asText());
  }

  @Test
  public void testGetDataset_v1AndV2_fieldsArrayPopulated() throws Exception {
    // DB_TABLE_META carries field definitions with tags — verify both V1 and V2 expose them
    client.createDataset(NAMESPACE_NAME, "fields_ds", DB_TABLE_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    JsonNode v1 =
        MAPPER.readTree(httpGet("/api/v1/namespaces/" + ns + "/datasets/fields_ds").body());
    JsonNode v2 =
        MAPPER.readTree(httpGet("/api/v2/namespaces/" + ns + "/datasets/fields_ds").body());

    assertThat(v1.path("fields").isArray()).isTrue();
    assertThat(v1.path("fields").size()).isGreaterThan(0);
    assertThat(v2.path("fields").isArray()).as("V2 fields is array").isTrue();
    assertThat(v2.path("fields").size())
        .as("V2 field count equals V1")
        .isEqualTo(v1.path("fields").size());
  }

  @Test
  public void testGetDataset_v1AndV2_tagsArrayPopulated() throws Exception {
    // DB_TABLE_META has PII tag set
    client.createDataset(NAMESPACE_NAME, "tags_ds", DB_TABLE_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    JsonNode v1 = MAPPER.readTree(httpGet("/api/v1/namespaces/" + ns + "/datasets/tags_ds").body());
    JsonNode v2 = MAPPER.readTree(httpGet("/api/v2/namespaces/" + ns + "/datasets/tags_ds").body());

    // Both must have a non-empty tags array
    assertThat(v1.path("tags").isArray()).isTrue();
    assertThat(v1.path("tags").size()).isGreaterThan(0);
    assertThat(v2.path("tags").isArray()).as("V2 tags is array").isTrue();
    assertThat(v2.path("tags").size()).as("V2 tag count >= 1").isGreaterThanOrEqualTo(1);

    // Collect tag names from each response
    Set<String> v1Tags = new java.util.HashSet<>();
    v1.path("tags").forEach(t -> v1Tags.add(t.asText()));
    Set<String> v2Tags = new java.util.HashSet<>();
    v2.path("tags").forEach(t -> v2Tags.add(t.asText()));
    assertThat(v2Tags).containsAll(v1Tags);
  }

  // ---------------------------------------------------------------------------
  // Tests — 404 / error-case parity
  // ---------------------------------------------------------------------------

  @Test
  public void testGetDataset_v2Returns404_datasetNotFound() throws Exception {
    HttpResponse<String> resp =
        httpGet("/api/v2/namespaces/" + enc(NAMESPACE_NAME) + "/datasets/no_such_dataset");
    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  public void testListDatasets_v2Returns404_namespaceNotFound() throws Exception {
    HttpResponse<String> resp = httpGet("/api/v2/namespaces/completely_missing_namespace/datasets");
    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  public void testListDatasets_v1AndV2_agreeOn404_forMissingNamespace() throws Exception {
    HttpResponse<String> v1Resp = httpGet("/api/v1/namespaces/missing_ns_xyz/datasets");
    HttpResponse<String> v2Resp = httpGet("/api/v2/namespaces/missing_ns_xyz/datasets");
    assertThat(v1Resp.statusCode()).as("V1 status for missing ns").isEqualTo(404);
    assertThat(v2Resp.statusCode()).as("V2 status for missing ns").isEqualTo(404);
  }

  // ---------------------------------------------------------------------------
  // Tests — mutation propagation
  // ---------------------------------------------------------------------------

  @Test
  public void testDeleteDataset_v2ShowsDeleted_andV2Returns404() throws Exception {
    client.createDataset(NAMESPACE_NAME, "delete_me_ds", DB_TABLE_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    // Confirm visible in V2 before delete
    assertThat(httpGet("/api/v2/namespaces/" + ns + "/datasets/delete_me_ds").statusCode())
        .isEqualTo(200);

    // Delete via V2 endpoint
    HttpResponse<String> deleteResp =
        httpDelete("/api/v2/namespaces/" + ns + "/datasets/delete_me_ds");
    assertThat(deleteResp.statusCode()).as("delete response").isEqualTo(200);

    // V1 and V2 must both return 404 after deletion
    assertThat(httpGet("/api/v1/namespaces/" + ns + "/datasets/delete_me_ds").statusCode())
        .as("V1 after delete")
        .isEqualTo(404);
    assertThat(httpGet("/api/v2/namespaces/" + ns + "/datasets/delete_me_ds").statusCode())
        .as("V2 after delete")
        .isEqualTo(404);
  }

  @Test
  public void testCreateOrUpdateDataset_visibleInBothV1AndV2() throws Exception {
    // Create via V1 REST API (PUT), then both endpoints must expose the dataset
    client.createDataset(NAMESPACE_NAME, "create_visible_ds", DB_TABLE_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    assertThat(httpGet("/api/v1/namespaces/" + ns + "/datasets/create_visible_ds").statusCode())
        .as("V1 GET after create")
        .isEqualTo(200);
    assertThat(httpGet("/api/v2/namespaces/" + ns + "/datasets/create_visible_ds").statusCode())
        .as("V2 GET after create + denorm populate")
        .isEqualTo(200);
  }

  // ---------------------------------------------------------------------------
  // Structural parity — full response shape compare. Original tests only checked
  // a hand-picked subset of fields; this catches V1 fields silently dropped from
  // V2 (the kind of regression that hit jobs.latestRuns / jobs.dataset_facets).
  // ---------------------------------------------------------------------------

  @Test
  public void testGetDataset_v1AndV2_fullStructuralParity() throws Exception {
    client.createDataset(NAMESPACE_NAME, "structural_parity_ds", DB_TABLE_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    JsonNode v1 =
        MAPPER.readTree(
            httpGet("/api/v1/namespaces/" + ns + "/datasets/structural_parity_ds").body());
    JsonNode v2 =
        MAPPER.readTree(
            httpGet("/api/v2/namespaces/" + ns + "/datasets/structural_parity_ds").body());

    // `lastModifiedAt` and the dataset version surface include UUIDs/timestamps that legitimately
    // differ across re-runs — the helper's default valueIgnoredKeys covers timestamps, and we
    // allowlist `currentVersion` because the field is a generated UUID.
    V1V2ParityAssertions.assertStructurallyEqual(v1, v2);
  }

  @Test
  public void testListDatasets_v1AndV2_fullStructuralParity() throws Exception {
    client.createDataset(NAMESPACE_NAME, "structural_list_ds_a", DB_TABLE_META);
    client.createDataset(NAMESPACE_NAME, "structural_list_ds_b", DB_TABLE_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    JsonNode v1 =
        MAPPER.readTree(httpGet("/api/v1/namespaces/" + ns + "/datasets?limit=200").body());
    JsonNode v2 =
        MAPPER.readTree(httpGet("/api/v2/namespaces/" + ns + "/datasets?limit=200").body());

    V1V2ParityAssertions.assertStructurallyEqual(v1, v2);
  }
}

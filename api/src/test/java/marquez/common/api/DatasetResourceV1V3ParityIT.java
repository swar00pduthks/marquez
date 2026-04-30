/*
 * Copyright 2018-2026 contributors to the Marquez project
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import marquez.BaseIntegrationTest;
import marquez.MarquezApp;
import marquez.api.JdbiUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Parity regression tests: V1 and V3 dataset endpoints.
 *
 * <p>V3 is AGE-graph-backed and populated by {@code GraphWriter} on lineage event ingestion. Both
 * V1 and V3 must surface datasets produced or consumed by OpenLineage events.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class DatasetResourceV1V3ParityIT extends BaseIntegrationTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @AfterEach
  public void tearDown() {
    JdbiUtils.cleanDatabase(MarquezApp.getJdbiInstanceForTesting());
  }

  private String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private HttpResponse<String> httpGet(String path) throws Exception {
    return http2.send(
        HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private List<JsonNode> extractList(String body, String arrayKey) throws Exception {
    JsonNode root = MAPPER.readTree(body);
    JsonNode arrayNode = root.isArray() ? root : root.path(arrayKey);
    List<JsonNode> result = new ArrayList<>();
    arrayNode.forEach(result::add);
    return result;
  }

  private String buildEvent(UUID runId, String ns, String job, String outDs) {
    return String.format(
        "{\"eventType\":\"COMPLETE\",\"eventTime\":\"%s\","
            + "\"run\":{\"runId\":\"%s\"},"
            + "\"job\":{\"namespace\":\"%s\",\"name\":\"%s\"},"
            + "\"inputs\":[],"
            + "\"outputs\":[{\"namespace\":\"%s\",\"name\":\"%s\"}],"
            + "\"producer\":\"https://github.com/OpenLineage/OpenLineage/blob/v1-0-0/client\","
            + "\"schemaURL\":\"https://openlineage.io/spec/1-0-1/OpenLineage.json#/definitions/RunEvent\"}",
        Instant.now(), runId, ns, job, ns, outDs);
  }

  private void ingest(String ns, String job, String ds) throws Exception {
    HttpResponse<String> resp = sendLineage(buildEvent(UUID.randomUUID(), ns, job, ds)).join();
    assertThat(resp.statusCode()).isEqualTo(201);
  }

  @Test
  public void testGlobalListDatasets_v3ContainsIngested_v1HasNoGlobalEndpoint() throws Exception {
    // V1 has no /api/v1/datasets global list (only namespace-scoped) — V3 introduces one.
    String ns = "ds_parity_ns";
    ingest(ns, "jp_1", "parity_ds_alpha");
    ingest(ns, "jp_2", "parity_ds_beta");
    ingest(ns, "jp_3", "parity_ds_gamma");

    HttpResponse<String> v1 = httpGet("/api/v1/datasets?limit=500");
    assertThat(v1.statusCode())
        .as("V1 has no global /datasets endpoint by design (documented gap)")
        .isEqualTo(404);

    HttpResponse<String> v3 = httpGet("/api/v3/datasets?limit=500");
    assertThat(v3.statusCode()).isEqualTo(200);

    Set<String> v3Names =
        extractList(v3.body(), "datasets").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    assertThat(v3Names).contains("parity_ds_alpha", "parity_ds_beta", "parity_ds_gamma");
  }

  @Test
  public void testNamespaceScopedListDatasets_v1AndV3_agree() throws Exception {
    String ns = "ns_scoped_ds";
    ingest(ns, "jsd_1", "scoped_ds_1");
    ingest(ns, "jsd_2", "scoped_ds_2");

    HttpResponse<String> v1 = httpGet("/api/v1/namespaces/" + enc(ns) + "/datasets?limit=500");
    HttpResponse<String> v3 = httpGet("/api/v3/namespaces/" + enc(ns) + "/datasets?limit=500");
    assertThat(v1.statusCode()).isEqualTo(200);
    assertThat(v3.statusCode()).isEqualTo(200);

    Set<String> v1Names =
        extractList(v1.body(), "datasets").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    Set<String> v3Names =
        extractList(v3.body(), "datasets").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    assertThat(v1Names).contains("scoped_ds_1", "scoped_ds_2");
    assertThat(v3Names).contains("scoped_ds_1", "scoped_ds_2");
  }

  @Test
  public void testGetDataset_v1AndV3_agreeOn200AndName() throws Exception {
    String ns = "get_ds_parity_ns";
    ingest(ns, "get_ds_job", "get_me_ds");

    HttpResponse<String> v1 =
        httpGet("/api/v1/namespaces/" + enc(ns) + "/datasets/" + enc("get_me_ds"));
    HttpResponse<String> v3 =
        httpGet("/api/v3/namespaces/" + enc(ns) + "/datasets/" + enc("get_me_ds"));
    assertThat(v1.statusCode()).isEqualTo(200);
    assertThat(v3.statusCode()).isEqualTo(200);

    assertThat(MAPPER.readTree(v1.body()).path("name").asText()).isEqualTo("get_me_ds");
    assertThat(MAPPER.readTree(v3.body()).path("name").asText()).isEqualTo("get_me_ds");
  }

  @Test
  public void testGetDataset_v1AndV3_agreeOn404() throws Exception {
    String ns = "missing_ds_ns";
    ingest(ns, "anchor_job", "anchor_ds");

    HttpResponse<String> v1 =
        httpGet("/api/v1/namespaces/" + enc(ns) + "/datasets/" + enc("no_such_ds"));
    HttpResponse<String> v3 =
        httpGet("/api/v3/namespaces/" + enc(ns) + "/datasets/" + enc("no_such_ds"));
    assertThat(v1.statusCode()).isEqualTo(404);
    assertThat(v3.statusCode()).isEqualTo(404);
  }

  @Test
  public void testHttpsUriNamespaceDatasets_v1AndV3_bothSee() throws Exception {
    String uriNs = "https://github.com/acme/v3-dataset-parity";
    ingest(uriNs, "uri_ds_job", "uri_dataset");

    HttpResponse<String> v1 =
        httpGet("/api/v1/namespaces/" + enc(uriNs) + "/datasets/" + enc("uri_dataset"));
    HttpResponse<String> v3 =
        httpGet("/api/v3/namespaces/" + enc(uriNs) + "/datasets/" + enc("uri_dataset"));
    assertThat(v1.statusCode()).as("V1 URI-ns dataset get").isEqualTo(200);
    assertThat(v3.statusCode()).as("V3 URI-ns dataset get").isEqualTo(200);
    assertThat(MAPPER.readTree(v3.body()).path("name").asText()).isEqualTo("uri_dataset");
  }
}

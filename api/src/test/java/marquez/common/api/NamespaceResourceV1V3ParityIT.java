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
 * Parity regression tests: V1 and V3 namespace endpoints must agree on entity identity.
 *
 * <p>V1 reads from the relational {@code namespaces} table. V3 reads from the Apache AGE graph
 * populated by {@code GraphWriter} on OpenLineage event ingestion. Therefore a namespace observed
 * via an ingested lineage event must be visible through both endpoints.
 *
 * <p>V3 is read-only (no PUT/POST/DELETE): every parity test ingests a lineage event first.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class NamespaceResourceV1V3ParityIT extends BaseIntegrationTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @AfterEach
  public void tearDown() {
    JdbiUtils.cleanDatabase(MarquezApp.getJdbiInstanceForTesting());
  }

  private HttpResponse<String> httpGet(String path) throws Exception {
    return http2.send(
        HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private List<JsonNode> extractList(String body, String arrayKey) throws Exception {
    JsonNode root = MAPPER.readTree(body);
    JsonNode arrayNode = root.isArray() ? root : root.path(arrayKey);
    List<JsonNode> result = new ArrayList<>();
    arrayNode.forEach(result::add);
    return result;
  }

  private String buildMinimalCompleteEvent(UUID runId, String ns, String job, String outDs) {
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

  private void ingestEvent(String ns, String job, String outDs) throws Exception {
    UUID runId = UUID.randomUUID();
    HttpResponse<String> resp =
        sendLineage(buildMinimalCompleteEvent(runId, ns, job, outDs)).join();
    assertThat(resp.statusCode()).isEqualTo(201);
  }

  @Test
  public void testListNamespaces_v1AndV3_containSameIngestedNames() throws Exception {
    ingestEvent("parity_ns_alpha", "job_a", "ds_a");
    ingestEvent("parity_ns_beta", "job_b", "ds_b");
    ingestEvent("parity_ns_gamma", "job_c", "ds_c");

    HttpResponse<String> v1 = httpGet("/api/v1/namespaces?limit=200");
    HttpResponse<String> v3 = httpGet("/api/v3/namespaces?limit=200");
    assertThat(v1.statusCode()).isEqualTo(200);
    assertThat(v3.statusCode()).isEqualTo(200);

    Set<String> v1Names =
        extractList(v1.body(), "namespaces").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    Set<String> v3Names =
        extractList(v3.body(), "namespaces").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    assertThat(v1Names).contains("parity_ns_alpha", "parity_ns_beta", "parity_ns_gamma");
    assertThat(v3Names)
        .as("V3 AGE-graph must contain every namespace ingested via OpenLineage events")
        .contains("parity_ns_alpha", "parity_ns_beta", "parity_ns_gamma");
  }

  @Test
  public void testGetNamespace_v1AndV3_agreeOnName() throws Exception {
    ingestEvent("parity_get_ns", "job_get", "ds_get");

    HttpResponse<String> v1 = httpGet("/api/v1/namespaces/" + enc("parity_get_ns"));
    HttpResponse<String> v3 = httpGet("/api/v3/namespaces/" + enc("parity_get_ns"));
    assertThat(v1.statusCode()).isEqualTo(200);
    assertThat(v3.statusCode()).isEqualTo(200);

    assertThat(MAPPER.readTree(v1.body()).path("name").asText()).isEqualTo("parity_get_ns");
    assertThat(MAPPER.readTree(v3.body()).path("name").asText()).isEqualTo("parity_get_ns");
  }

  @Test
  public void testGetNamespace_v1AndV3_agreeOn404() throws Exception {
    HttpResponse<String> v1 = httpGet("/api/v1/namespaces/no_such_ns_xyz");
    HttpResponse<String> v3 = httpGet("/api/v3/namespaces/no_such_ns_xyz");
    assertThat(v1.statusCode()).isEqualTo(404);
    assertThat(v3.statusCode()).isEqualTo(404);
  }

  @Test
  public void testHttpsUriNamespace_v1AndV3_bothVisible() throws Exception {
    String uriNs = "https://github.com/acme/v3-parity-repo";
    ingestEvent(uriNs, "uri_job", "uri_ds");

    HttpResponse<String> v1 = httpGet("/api/v1/namespaces/" + enc(uriNs));
    HttpResponse<String> v3 = httpGet("/api/v3/namespaces/" + enc(uriNs));
    assertThat(v1.statusCode()).as("V1 https:// ns get").isEqualTo(200);
    assertThat(v3.statusCode()).as("V3 https:// ns get").isEqualTo(200);

    assertThat(MAPPER.readTree(v1.body()).path("name").asText()).isEqualTo(uriNs);
    assertThat(MAPPER.readTree(v3.body()).path("name").asText()).isEqualTo(uriNs);

    Set<String> v3Names =
        extractList(httpGet("/api/v3/namespaces?limit=200").body(), "namespaces").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    assertThat(v3Names).contains(uriNs);
  }

  @Test
  public void testMultiSchemeUriNamespaces_v1AndV3_allVisible() throws Exception {
    String[] uriNamespaces = {
      "https://gitlab.com/team/v3-proj",
      "postgres://prod.example.com:5432/warehouse",
      "s3://acme-v3-datalake",
      "kafka://broker-v3.internal:9092"
    };
    for (int i = 0; i < uriNamespaces.length; i++) {
      ingestEvent(uriNamespaces[i], "multi_job_" + i, "multi_ds_" + i);
    }

    Set<String> v1Names =
        extractList(httpGet("/api/v1/namespaces?limit=500").body(), "namespaces").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    Set<String> v3Names =
        extractList(httpGet("/api/v3/namespaces?limit=500").body(), "namespaces").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    for (String ns : uriNamespaces) {
      assertThat(v1Names).as("V1 must list URI ns " + ns).contains(ns);
      assertThat(v3Names).as("V3 must list URI ns " + ns).contains(ns);
    }
  }
}

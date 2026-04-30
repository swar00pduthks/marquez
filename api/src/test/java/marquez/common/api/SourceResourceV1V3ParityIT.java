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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Parity regression tests: V1 and V3 source endpoints.
 *
 * <p>V3 is AGE-graph-backed and populated by {@code GraphWriter} on lineage event ingestion. V3
 * exposes only a list endpoint. Sources created explicitly via V1 PUT are NOT written to the AGE
 * graph; the only way sources become V3-visible is through the OpenLineage dataSource facet at
 * ingestion time. This test suite documents that contract.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class SourceResourceV1V3ParityIT extends BaseIntegrationTest {

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

  private List<JsonNode> extractList(String body, String arrayKey) throws Exception {
    JsonNode root = MAPPER.readTree(body);
    JsonNode arrayNode = root.isArray() ? root : root.path(arrayKey);
    List<JsonNode> result = new ArrayList<>();
    arrayNode.forEach(result::add);
    return result;
  }

  private String buildEventWithDataSourceFacet(
      UUID runId,
      String ns,
      String job,
      String outDs,
      String sourceName,
      String sourceUri,
      String sourceType) {
    return String.format(
        "{\"eventType\":\"COMPLETE\",\"eventTime\":\"%s\","
            + "\"run\":{\"runId\":\"%s\"},"
            + "\"job\":{\"namespace\":\"%s\",\"name\":\"%s\"},"
            + "\"inputs\":[],"
            + "\"outputs\":[{\"namespace\":\"%s\",\"name\":\"%s\","
            + "  \"facets\":{\"dataSource\":{\"_producer\":\"p\",\"_schemaURL\":\"s\","
            + "    \"name\":\"%s\",\"uri\":\"%s\"}}}],"
            + "\"producer\":\"https://github.com/OpenLineage/OpenLineage/blob/v1-0-0/client\","
            + "\"schemaURL\":\"https://openlineage.io/spec/1-0-1/OpenLineage.json#/definitions/RunEvent\"}",
        Instant.now(), runId, ns, job, ns, outDs, sourceName, sourceUri);
  }

  private void ingest(String ns, String job, String outDs, String sourceName, String uri)
      throws Exception {
    UUID runId = UUID.randomUUID();
    HttpResponse<String> resp =
        sendLineage(
                buildEventWithDataSourceFacet(runId, ns, job, outDs, sourceName, uri, "POSTGRESQL"))
            .join();
    assertThat(resp.statusCode()).isEqualTo(201);
  }

  @Test
  public void testListSources_v1AndV3_containSameIngestedNames() throws Exception {
    ingest("parity_ns_src1", "j1", "d1", "parity_src_alpha", "postgres://h1/db");
    ingest("parity_ns_src2", "j2", "d2", "parity_src_beta", "postgres://h2/db");
    ingest("parity_ns_src3", "j3", "d3", "parity_src_gamma", "postgres://h3/db");

    HttpResponse<String> v1 = httpGet("/api/v1/sources?limit=500");
    HttpResponse<String> v3 = httpGet("/api/v3/sources?limit=500");
    assertThat(v1.statusCode()).isEqualTo(200);
    assertThat(v3.statusCode()).isEqualTo(200);

    Set<String> v1Names =
        extractList(v1.body(), "sources").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    Set<String> v3Names =
        extractList(v3.body(), "sources").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    assertThat(v1Names).contains("parity_src_alpha", "parity_src_beta", "parity_src_gamma");
    assertThat(v3Names)
        .as("V3 AGE graph must include sources written from OpenLineage dataSource facets")
        .contains("parity_src_alpha", "parity_src_beta", "parity_src_gamma");
  }

  @Test
  public void testV1PutCreatedSource_notPresentInV3_documentsKnownGap() throws Exception {
    // V1 PUT creates a source row directly; V3 only sees AGE-graph nodes.
    createSource("v1_only_src_never_in_v3");

    Set<String> v1Names =
        extractList(httpGet("/api/v1/sources?limit=500").body(), "sources").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    Set<String> v3Names =
        extractList(httpGet("/api/v3/sources?limit=500").body(), "sources").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    assertThat(v1Names).contains("v1_only_src_never_in_v3");
    assertThat(v3Names)
        .as("V1-PUT sources are not ingested into the AGE graph — known V3 gap")
        .doesNotContain("v1_only_src_never_in_v3");
  }
}

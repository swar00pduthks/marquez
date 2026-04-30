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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import marquez.BaseIntegrationTest;
import marquez.MarquezApp;
import marquez.api.JdbiUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Parity regression tests: V1 and V3 tag endpoints.
 *
 * <p>V3 tag endpoint reads {@code :Tag} nodes from the AGE graph, but {@code GraphWriter} does NOT
 * currently write {@code :Tag} nodes on lineage ingestion, nor does V1 tag PUT touch the AGE graph.
 * This suite pins that documented gap so any future work to wire Tag nodes into GraphWriter has a
 * failing test to flip to green.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class TagResourceV1V3ParityIT extends BaseIntegrationTest {

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

  private HttpResponse<String> httpPut(String path, String body) throws Exception {
    return http2.send(
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
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

  @Test
  public void testV3TagEndpoint_reachable_returns200() throws Exception {
    HttpResponse<String> v3 = httpGet("/api/v3/tags?limit=50");
    assertThat(v3.statusCode()).isEqualTo(200);

    JsonNode root = MAPPER.readTree(v3.body());
    assertThat(root.has("tags")).isTrue();
    assertThat(root.path("tags").isArray()).isTrue();
  }

  @Test
  public void testV1TagsNotWrittenToAgeGraph_documentsKnownGap() throws Exception {
    httpPut("/api/v1/tags/v1_only_tag_alpha", "{\"description\":\"a\"}");
    httpPut("/api/v1/tags/v1_only_tag_beta", "{\"description\":\"b\"}");

    Set<String> v1Names =
        extractList(httpGet("/api/v1/tags?limit=200").body(), "tags").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    assertThat(v1Names).contains("v1_only_tag_alpha", "v1_only_tag_beta");

    Set<String> v3Names =
        extractList(httpGet("/api/v3/tags?limit=200").body(), "tags").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    assertThat(v3Names)
        .as(
            "V3 tag endpoint reads :Tag nodes from AGE graph; GraphWriter does not write Tag "
                + "nodes yet. Known gap — flip this assertion to containsAll(v1Names) once wired.")
        .doesNotContain("v1_only_tag_alpha", "v1_only_tag_beta");
  }
}

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
 * Parity regression tests: V1 and V2 tag endpoints must agree.
 *
 * <p>V1 and V2 both delegate to TagService; this test guards against a future drift if V2 is ever
 * reimplemented on a denormalized view.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class TagResourceV1V2ParityIT extends BaseIntegrationTest {

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
  public void testListTags_v1AndV2_containSameNames() throws Exception {
    httpPut("/api/v2/tags/parity_tag_alpha", "{\"description\":\"a\"}");
    httpPut("/api/v2/tags/parity_tag_beta", "{\"description\":\"b\"}");
    httpPut("/api/v2/tags/parity_tag_gamma", "{\"description\":\"c\"}");

    HttpResponse<String> v1 = httpGet("/api/v1/tags?limit=200");
    HttpResponse<String> v2 = httpGet("/api/v2/tags?limit=200");
    assertThat(v1.statusCode()).isEqualTo(200);
    assertThat(v2.statusCode()).isEqualTo(200);

    Set<String> v1Names =
        extractList(v1.body(), "tags").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    Set<String> v2Names =
        extractList(v2.body(), "tags").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    assertThat(v2Names).containsAll(v1Names);
    assertThat(v2Names).contains("parity_tag_alpha", "parity_tag_beta", "parity_tag_gamma");
  }

  @Test
  public void testCreateTag_v2_isVisibleInV1List() throws Exception {
    HttpResponse<String> put =
        httpPut("/api/v2/tags/created_via_v2", "{\"description\":\"created via v2\"}");
    assertThat(put.statusCode()).isEqualTo(200);

    Set<String> v1Names =
        extractList(httpGet("/api/v1/tags?limit=200").body(), "tags").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    assertThat(v1Names).contains("created_via_v2");
  }

  @Test
  public void testCreateTag_v1_isVisibleInV2List() throws Exception {
    HttpResponse<String> put =
        httpPut("/api/v1/tags/created_via_v1", "{\"description\":\"created via v1\"}");
    assertThat(put.statusCode()).isEqualTo(200);

    Set<String> v2Names =
        extractList(httpGet("/api/v2/tags?limit=200").body(), "tags").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    assertThat(v2Names).contains("created_via_v1");
  }

  // ---------------------------------------------------------------------------
  // Structural parity — full response shape compare. Original tests only matched
  // tag names; this catches any V1 field silently dropped from V2.
  // ---------------------------------------------------------------------------

  @Test
  public void testListTags_v1AndV2_fullStructuralParity() throws Exception {
    httpPut("/api/v2/tags/structural_tag_alpha", "{\"description\":\"alpha desc\"}");
    httpPut("/api/v2/tags/structural_tag_beta", "{\"description\":\"beta desc\"}");

    JsonNode v1 = MAPPER.readTree(httpGet("/api/v1/tags?limit=200").body());
    JsonNode v2 = MAPPER.readTree(httpGet("/api/v2/tags?limit=200").body());

    V1V2ParityAssertions.assertStructurallyEqual(v1, v2);
  }

  @Test
  public void testListTags_v2_paginationIsConsistent() throws Exception {
    for (int i = 0; i < 6; i++) {
      httpPut("/api/v2/tags/page_tag_" + String.format("%02d", i), "{\"description\":\"d\"}");
    }

    List<JsonNode> page1 = extractList(httpGet("/api/v2/tags?limit=3&offset=0").body(), "tags");
    List<JsonNode> page2 = extractList(httpGet("/api/v2/tags?limit=3&offset=3").body(), "tags");

    assertThat(page1).hasSize(3);
    assertThat(page2).hasSize(3);

    Set<String> p1 = page1.stream().map(n -> n.path("name").asText()).collect(Collectors.toSet());
    Set<String> p2 = page2.stream().map(n -> n.path("name").asText()).collect(Collectors.toSet());
    assertThat(p1).doesNotContainAnyElementsOf(p2);
  }
}

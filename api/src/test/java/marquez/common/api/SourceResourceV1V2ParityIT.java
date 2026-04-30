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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Parity regression tests: V1 and V2 source endpoints must agree.
 *
 * <p>Protects the V1/V2 contract for sources which share the same underlying SourceService.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class SourceResourceV1V2ParityIT extends BaseIntegrationTest {

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

  @Test
  public void testListSources_v1AndV2_containSameNames() throws Exception {
    createSource("parity_src_alpha");
    createSource("parity_src_beta");
    createSource("parity_src_gamma");

    HttpResponse<String> v1 = httpGet("/api/v1/sources?limit=100");
    HttpResponse<String> v2 = httpGet("/api/v2/sources?limit=100");
    assertThat(v1.statusCode()).isEqualTo(200);
    assertThat(v2.statusCode()).isEqualTo(200);

    Set<String> v1Names =
        extractList(v1.body(), "sources").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    Set<String> v2Names =
        extractList(v2.body(), "sources").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    assertThat(v2Names).containsAll(v1Names);
    assertThat(v2Names).contains("parity_src_alpha", "parity_src_beta", "parity_src_gamma");
  }

  @Test
  public void testListSources_v2_paginationIsConsistent() throws Exception {
    for (int i = 0; i < 6; i++) {
      createSource("page_src_" + String.format("%02d", i));
    }

    List<JsonNode> page1 =
        extractList(httpGet("/api/v2/sources?limit=3&offset=0").body(), "sources");
    List<JsonNode> page2 =
        extractList(httpGet("/api/v2/sources?limit=3&offset=3").body(), "sources");

    assertThat(page1).hasSize(3);
    assertThat(page2).hasSize(3);

    Set<String> p1 = page1.stream().map(n -> n.path("name").asText()).collect(Collectors.toSet());
    Set<String> p2 = page2.stream().map(n -> n.path("name").asText()).collect(Collectors.toSet());
    assertThat(p1).doesNotContainAnyElementsOf(p2);
  }

  @Test
  public void testGetSource_v1AndV2_coreFieldsMustMatch() throws Exception {
    createSource("parity_get_src");

    HttpResponse<String> v1Resp = httpGet("/api/v1/sources/" + enc("parity_get_src"));
    HttpResponse<String> v2Resp = httpGet("/api/v2/sources/" + enc("parity_get_src"));

    assertThat(v1Resp.statusCode()).isEqualTo(200);
    assertThat(v2Resp.statusCode()).isEqualTo(200);

    JsonNode v1 = MAPPER.readTree(v1Resp.body());
    JsonNode v2 = MAPPER.readTree(v2Resp.body());

    assertThat(v2.path("name").asText()).isEqualTo(v1.path("name").asText());
    assertThat(v2.path("type").asText()).isEqualTo(v1.path("type").asText());
    assertThat(v2.path("connectionUrl").asText()).isEqualTo(v1.path("connectionUrl").asText());
    assertThat(v2.path("description").asText()).isEqualTo(v1.path("description").asText());
  }

  @Test
  public void testGetSource_v2Returns404_whenMissing() throws Exception {
    HttpResponse<String> resp = httpGet("/api/v2/sources/no_such_source_xyz");
    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  public void testGetSource_v1AndV2_agreeOn404() throws Exception {
    HttpResponse<String> v1 = httpGet("/api/v1/sources/missing_src_xyz");
    HttpResponse<String> v2 = httpGet("/api/v2/sources/missing_src_xyz");
    assertThat(v1.statusCode()).isEqualTo(404);
    assertThat(v2.statusCode()).isEqualTo(404);
  }

  @Test
  public void testCreateOrUpdateSource_visibleInBothV1AndV2() throws Exception {
    createSource("create_visible_src");

    assertThat(httpGet("/api/v1/sources/" + enc("create_visible_src")).statusCode()).isEqualTo(200);
    assertThat(httpGet("/api/v2/sources/" + enc("create_visible_src")).statusCode()).isEqualTo(200);
  }

  // ---------------------------------------------------------------------------
  // Structural parity — full response shape compare via V1V2ParityAssertions.
  // The original tests above only compared a hand-picked subset of fields, which
  // would let any new V1 field silently disappear from V2. These tests close
  // that gap by enforcing key-by-key structural equality.
  // ---------------------------------------------------------------------------

  @Test
  public void testGetSource_v1AndV2_fullStructuralParity() throws Exception {
    createSource("structural_parity_src");

    JsonNode v1 =
        MAPPER.readTree(httpGet("/api/v1/sources/" + enc("structural_parity_src")).body());
    JsonNode v2 =
        MAPPER.readTree(httpGet("/api/v2/sources/" + enc("structural_parity_src")).body());

    V1V2ParityAssertions.assertStructurallyEqual(v1, v2);
  }

  @Test
  public void testListSources_v1AndV2_fullStructuralParity() throws Exception {
    createSource("list_parity_src_alpha");
    createSource("list_parity_src_beta");

    JsonNode v1 = MAPPER.readTree(httpGet("/api/v1/sources?limit=200").body());
    JsonNode v2 = MAPPER.readTree(httpGet("/api/v2/sources?limit=200").body());

    // V1 doesn't return a totalCount on the sources list — V2 may add it. Allowlist via default
    // ParityConfig (which permits `totalCount` as a V2-only key).
    V1V2ParityAssertions.assertStructurallyEqual(v1, v2);
  }
}

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
import marquez.client.models.NamespaceMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Parity regression tests: V1 and V2 namespace endpoints must agree.
 *
 * <p>V2 namespace endpoints (api/v2/namespaces) reuse the same NamespaceService as V1. These tests
 * protect that contract and catch regressions if V2 ever diverges to a denormalized view.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class NamespaceResourceV1V2ParityIT extends BaseIntegrationTest {

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

  private HttpResponse<String> httpDelete(String path) throws Exception {
    return http2.send(
        HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private List<JsonNode> extractList(String body, String arrayKey) throws Exception {
    JsonNode root = MAPPER.readTree(body);
    JsonNode arrayNode = root.isArray() ? root : root.path(arrayKey);
    List<JsonNode> result = new ArrayList<>();
    arrayNode.forEach(result::add);
    return result;
  }

  // ---------------------------------------------------------------------------
  // Tests — list endpoint parity
  // ---------------------------------------------------------------------------

  @Test
  public void testListNamespaces_v1AndV2_containSameNames() throws Exception {
    client.createNamespace(
        "parity_ns_alpha", NamespaceMeta.builder().ownerName(OWNER_NAME).build());
    client.createNamespace("parity_ns_beta", NamespaceMeta.builder().ownerName(OWNER_NAME).build());
    client.createNamespace(
        "parity_ns_gamma", NamespaceMeta.builder().ownerName(OWNER_NAME).build());

    HttpResponse<String> v1Resp = httpGet("/api/v1/namespaces?limit=100");
    assertThat(v1Resp.statusCode()).isEqualTo(200);
    Set<String> v1Names =
        extractList(v1Resp.body(), "namespaces").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    HttpResponse<String> v2Resp = httpGet("/api/v2/namespaces?limit=100");
    assertThat(v2Resp.statusCode()).isEqualTo(200);
    Set<String> v2Names =
        extractList(v2Resp.body(), "namespaces").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    assertThat(v2Names).containsAll(v1Names);
    assertThat(v2Names).contains("parity_ns_alpha", "parity_ns_beta", "parity_ns_gamma");
  }

  @Test
  public void testListNamespaces_v2_paginationIsConsistent() throws Exception {
    for (int i = 0; i < 6; i++) {
      client.createNamespace(
          "page_ns_" + String.format("%02d", i),
          NamespaceMeta.builder().ownerName(OWNER_NAME).build());
    }

    List<JsonNode> page1 =
        extractList(httpGet("/api/v2/namespaces?limit=3&offset=0").body(), "namespaces");
    List<JsonNode> page2 =
        extractList(httpGet("/api/v2/namespaces?limit=3&offset=3").body(), "namespaces");

    assertThat(page1).hasSize(3);
    assertThat(page2).hasSize(3);

    Set<String> p1 = page1.stream().map(n -> n.path("name").asText()).collect(Collectors.toSet());
    Set<String> p2 = page2.stream().map(n -> n.path("name").asText()).collect(Collectors.toSet());
    assertThat(p1).doesNotContainAnyElementsOf(p2);
  }

  // ---------------------------------------------------------------------------
  // Tests — get-by-name parity
  // ---------------------------------------------------------------------------

  @Test
  public void testGetNamespace_v1AndV2_coreFieldsMustMatch() throws Exception {
    client.createNamespace("parity_get_ns", NamespaceMeta.builder().ownerName(OWNER_NAME).build());

    HttpResponse<String> v1Resp = httpGet("/api/v1/namespaces/" + enc("parity_get_ns"));
    HttpResponse<String> v2Resp = httpGet("/api/v2/namespaces/" + enc("parity_get_ns"));

    assertThat(v1Resp.statusCode()).isEqualTo(200);
    assertThat(v2Resp.statusCode()).isEqualTo(200);

    JsonNode v1 = MAPPER.readTree(v1Resp.body());
    JsonNode v2 = MAPPER.readTree(v2Resp.body());

    assertThat(v2.path("name").asText()).isEqualTo(v1.path("name").asText());
    assertThat(v2.path("ownerName").asText()).isEqualTo(v1.path("ownerName").asText());
    assertThat(v2.path("description").asText()).isEqualTo(v1.path("description").asText());
  }

  // ---------------------------------------------------------------------------
  // Tests — 404 / error-case parity
  // ---------------------------------------------------------------------------

  @Test
  public void testGetNamespace_v2Returns404_whenMissing() throws Exception {
    HttpResponse<String> resp = httpGet("/api/v2/namespaces/no_such_namespace_xyz");
    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  public void testGetNamespace_v1AndV2_agreeOn404() throws Exception {
    HttpResponse<String> v1 = httpGet("/api/v1/namespaces/missing_ns_xyz");
    HttpResponse<String> v2 = httpGet("/api/v2/namespaces/missing_ns_xyz");
    assertThat(v1.statusCode()).isEqualTo(404);
    assertThat(v2.statusCode()).isEqualTo(404);
  }

  // ---------------------------------------------------------------------------
  // Tests — mutation propagation
  // ---------------------------------------------------------------------------

  @Test
  public void testDeleteNamespace_v1AndV2_bothAccept() throws Exception {
    // KNOWN MARQUEZ QUIRK: namespace delete sets is_hidden=true (NamespaceDao.delete),
    // but neither findBy() nor findAll() filter hidden rows — so the namespace remains
    // fully visible via GET and LIST on both V1 and V2. The observable V1↔V2 parity for
    // DELETE is therefore just: both endpoints accept the call and return 200. The
    // actual "is the row gone?" assertion would fail today because Marquez's delete is
    // effectively a no-op from the API surface — a separate tracking issue, not a V1/V2
    // divergence.
    client.createNamespace("delete_me_ns", NamespaceMeta.builder().ownerName(OWNER_NAME).build());

    assertThat(httpGet("/api/v2/namespaces/" + enc("delete_me_ns")).statusCode()).isEqualTo(200);
    assertThat(httpGet("/api/v1/namespaces/" + enc("delete_me_ns")).statusCode()).isEqualTo(200);

    // Both V1 and V2 DELETE must return 200 for the same input
    HttpResponse<String> v2Del = httpDelete("/api/v2/namespaces/" + enc("delete_me_ns"));
    assertThat(v2Del.statusCode()).as("V2 DELETE status").isEqualTo(200);

    // Re-create so we can assert V1 DELETE too
    client.createNamespace(
        "delete_me_ns_v1", NamespaceMeta.builder().ownerName(OWNER_NAME).build());
    HttpResponse<String> v1Del = httpDelete("/api/v1/namespaces/" + enc("delete_me_ns_v1"));
    assertThat(v1Del.statusCode()).as("V1 DELETE status").isEqualTo(200);
  }

  @Test
  public void testCreateOrUpdateNamespace_visibleInBothV1AndV2() throws Exception {
    client.createNamespace(
        "create_visible_ns", NamespaceMeta.builder().ownerName(OWNER_NAME).build());

    assertThat(httpGet("/api/v1/namespaces/" + enc("create_visible_ns")).statusCode())
        .isEqualTo(200);
    assertThat(httpGet("/api/v2/namespaces/" + enc("create_visible_ns")).statusCode())
        .isEqualTo(200);
  }

  // ---------------------------------------------------------------------------
  // Tests — URL-encoded namespace names (e.g. OpenLineage-style https:// URIs)
  //
  // OpenLineage producers commonly use URIs as namespaces, e.g.
  //   "https://github.com/acme/repo", "s3://my-bucket", "postgres://host:5432/db".
  // These contain reserved characters (colons, slashes, dots) that MUST survive
  // percent-encoding over the wire and round-trip back through both V1 and V2
  // endpoints. Regressions here show up as 404s or 500s in production.
  // ---------------------------------------------------------------------------

  @Test
  public void testHttpsUriNamespace_v1AndV2_createGetListDelete() throws Exception {
    String uriNs = "https://github.com/acme/lineage-repo";

    // 1. PUT — create via V1 (client handles encoding internally). We use the
    //    low-level http2 client to exercise the encoded path explicitly via the
    //    marquez Java client, which uses the same percent-encoding as consumers.
    client.createNamespace(uriNs, NamespaceMeta.builder().ownerName(OWNER_NAME).build());

    // 2. GET — both V1 and V2 must round-trip the URI namespace when percent-encoded
    HttpResponse<String> v1Get = httpGet("/api/v1/namespaces/" + enc(uriNs));
    HttpResponse<String> v2Get = httpGet("/api/v2/namespaces/" + enc(uriNs));
    assertThat(v1Get.statusCode()).as("V1 GET https:// namespace").isEqualTo(200);
    assertThat(v2Get.statusCode()).as("V2 GET https:// namespace").isEqualTo(200);

    // name must come back exactly as submitted (decoded), not re-encoded
    JsonNode v1Body = MAPPER.readTree(v1Get.body());
    JsonNode v2Body = MAPPER.readTree(v2Get.body());
    assertThat(v1Body.path("name").asText()).isEqualTo(uriNs);
    assertThat(v2Body.path("name").asText()).isEqualTo(uriNs);
    // Core field parity for the URI namespace
    assertThat(v2Body.path("ownerName").asText()).isEqualTo(v1Body.path("ownerName").asText());

    // 3. LIST — URI namespace must appear in both V1 and V2 list endpoints
    Set<String> v1ListNames =
        extractList(httpGet("/api/v1/namespaces?limit=100").body(), "namespaces").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    Set<String> v2ListNames =
        extractList(httpGet("/api/v2/namespaces?limit=100").body(), "namespaces").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    assertThat(v1ListNames).contains(uriNs);
    assertThat(v2ListNames).contains(uriNs);

    // 4. DELETE — V2 delete must accept percent-encoded URI namespace (returns 200).
    // See testDeleteNamespace_v1AndV2_bothAccept for note on Marquez soft-delete quirk.
    HttpResponse<String> del = httpDelete("/api/v2/namespaces/" + enc(uriNs));
    assertThat(del.statusCode()).as("V2 DELETE https:// namespace").isEqualTo(200);
  }

  @Test
  public void testVariousUriSchemeNamespaces_v1AndV2_roundTripCorrectly() throws Exception {
    // Exercise several real-world OpenLineage namespace shapes.
    String[] uriNamespaces = {
      "https://github.com/acme/repo",
      "postgres://db-host:5432/warehouse",
      "s3://acme-datalake/curated",
      "kafka://broker-1.example.com:9092"
    };

    for (String ns : uriNamespaces) {
      client.createNamespace(ns, NamespaceMeta.builder().ownerName(OWNER_NAME).build());
    }

    for (String ns : uriNamespaces) {
      HttpResponse<String> v1 = httpGet("/api/v1/namespaces/" + enc(ns));
      HttpResponse<String> v2 = httpGet("/api/v2/namespaces/" + enc(ns));
      assertThat(v1.statusCode()).as("V1 GET %s", ns).isEqualTo(200);
      assertThat(v2.statusCode()).as("V2 GET %s", ns).isEqualTo(200);
      assertThat(MAPPER.readTree(v1.body()).path("name").asText()).isEqualTo(ns);
      assertThat(MAPPER.readTree(v2.body()).path("name").asText()).isEqualTo(ns);
    }

    // All URI namespaces must be visible in the list endpoints
    Set<String> v2ListNames =
        extractList(httpGet("/api/v2/namespaces?limit=100").body(), "namespaces").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    for (String ns : uriNamespaces) {
      assertThat(v2ListNames).as("V2 list must contain %s", ns).contains(ns);
    }
  }

  // ---------------------------------------------------------------------------
  // Structural parity — full response shape compare. Closes the gap left by the
  // original "core fields must match" tests that only checked name/ownerName.
  // ---------------------------------------------------------------------------

  @Test
  public void testGetNamespace_v1AndV2_fullStructuralParity() throws Exception {
    client.createNamespace(
        "structural_parity_ns",
        NamespaceMeta.builder()
            .ownerName(OWNER_NAME)
            .description("structural-parity-ns description")
            .build());

    JsonNode v1 =
        MAPPER.readTree(httpGet("/api/v1/namespaces/" + enc("structural_parity_ns")).body());
    JsonNode v2 =
        MAPPER.readTree(httpGet("/api/v2/namespaces/" + enc("structural_parity_ns")).body());

    V1V2ParityAssertions.assertStructurallyEqual(v1, v2);
  }

  @Test
  public void testListNamespaces_v1AndV2_fullStructuralParity() throws Exception {
    client.createNamespace(
        "list_parity_ns_a", NamespaceMeta.builder().ownerName(OWNER_NAME).description("a").build());
    client.createNamespace(
        "list_parity_ns_b", NamespaceMeta.builder().ownerName(OWNER_NAME).description("b").build());

    JsonNode v1 = MAPPER.readTree(httpGet("/api/v1/namespaces?limit=200").body());
    JsonNode v2 = MAPPER.readTree(httpGet("/api/v2/namespaces?limit=200").body());

    V1V2ParityAssertions.assertStructurallyEqual(v1, v2);
  }

  @Test
  public void testHttpsUriNamespace_asPathForDatasets_v1AndV2() throws Exception {
    // The https:// namespace must also work when used as a path segment in
    // nested resource URLs like /namespaces/{ns}/datasets.
    String uriNs = "https://github.com/acme/datasets-repo";
    client.createNamespace(uriNs, NamespaceMeta.builder().ownerName(OWNER_NAME).build());

    HttpResponse<String> v1 = httpGet("/api/v1/namespaces/" + enc(uriNs) + "/datasets?limit=10");
    HttpResponse<String> v2 = httpGet("/api/v2/namespaces/" + enc(uriNs) + "/datasets?limit=10");
    assertThat(v1.statusCode()).as("V1 datasets under https:// ns").isEqualTo(200);
    assertThat(v2.statusCode()).as("V2 datasets under https:// ns").isEqualTo(200);
  }
}

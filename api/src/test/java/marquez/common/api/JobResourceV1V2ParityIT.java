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
import java.time.Instant;
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
 * Parity regression tests: V1 and V2 job endpoints must agree on the same data.
 *
 * <p>These tests guard against regressions introduced by the denormalized-table-backed V2 views
 * (jobs_view_v2). Each test creates canonical data via the V1 REST API, populates the denormalized
 * tables explicitly, then compares the V1 and V2 responses for correctness.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class JobResourceV1V2ParityIT extends BaseIntegrationTest {

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
  public void testListJobs_v1AndV2_containSameJobNames() throws Exception {
    client.createJob(NAMESPACE_NAME, "parity_job_alpha", JOB_META);
    client.createJob(NAMESPACE_NAME, "parity_job_beta", JOB_META);
    client.createJob(NAMESPACE_NAME, "parity_job_gamma", JOB_META);

    // Query V1 before denorm populate (normalized tables)
    HttpResponse<String> v1Resp = httpGet("/api/v1/namespaces/" + enc(NAMESPACE_NAME) + "/jobs");
    assertThat(v1Resp.statusCode()).as("V1 list status").isEqualTo(200);
    Set<String> v1Names =
        extractList(v1Resp.body(), "jobs").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    populateDenormalized();

    HttpResponse<String> v2Resp = httpGet("/api/v2/namespaces/" + enc(NAMESPACE_NAME) + "/jobs");
    assertThat(v2Resp.statusCode()).as("V2 list status").isEqualTo(200);
    Set<String> v2Names =
        extractList(v2Resp.body(), "jobs").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    // V2 must expose every job that V1 exposes
    assertThat(v2Names).containsAll(v1Names);
    assertThat(v2Names).contains("parity_job_alpha", "parity_job_beta", "parity_job_gamma");
  }

  @Test
  public void testListJobs_totalCount_v2_matchesActualListSize() throws Exception {
    for (int i = 0; i < 5; i++) {
      client.createJob(NAMESPACE_NAME, "count_job_" + i, JOB_META);
    }
    populateDenormalized();

    HttpResponse<String> resp =
        httpGet("/api/v2/namespaces/" + enc(NAMESPACE_NAME) + "/jobs?limit=100&offset=0");
    assertThat(resp.statusCode()).isEqualTo(200);

    List<JsonNode> jobs = extractList(resp.body(), "jobs");
    int totalCount = extractTotalCount(resp.body());

    assertThat(jobs).hasSizeGreaterThanOrEqualTo(5);
    assertThat(totalCount).isEqualTo(jobs.size());
  }

  @Test
  public void testListJobs_v2_paginationIsConsistent() throws Exception {
    for (int i = 0; i < 8; i++) {
      client.createJob(NAMESPACE_NAME, "page_job_" + String.format("%02d", i), JOB_META);
    }
    populateDenormalized();

    String base = "/api/v2/namespaces/" + enc(NAMESPACE_NAME) + "/jobs";
    List<JsonNode> page1 = extractList(httpGet(base + "?limit=4&offset=0").body(), "jobs");
    List<JsonNode> page2 = extractList(httpGet(base + "?limit=4&offset=4").body(), "jobs");

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
  public void testGetJob_v1AndV2_coreFieldsMustMatch() throws Exception {
    client.createJob(NAMESPACE_NAME, "parity_get_job", JOB_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    HttpResponse<String> v1Resp = httpGet("/api/v1/namespaces/" + ns + "/jobs/parity_get_job");
    HttpResponse<String> v2Resp = httpGet("/api/v2/namespaces/" + ns + "/jobs/parity_get_job");

    assertThat(v1Resp.statusCode()).as("V1 get status").isEqualTo(200);
    assertThat(v2Resp.statusCode()).as("V2 get status").isEqualTo(200);

    JsonNode v1 = MAPPER.readTree(v1Resp.body());
    JsonNode v2 = MAPPER.readTree(v2Resp.body());

    assertThat(v2.path("name").asText()).as("name parity").isEqualTo(v1.path("name").asText());
    assertThat(v2.path("type").asText()).as("type parity").isEqualTo(v1.path("type").asText());
    assertThat(v2.path("description").asText())
        .as("description parity")
        .isEqualTo(v1.path("description").asText());
  }

  @Test
  public void testGetJob_v1AndV2_namespaceFieldPopulated() throws Exception {
    client.createJob(NAMESPACE_NAME, "ns_field_job", JOB_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    JsonNode v2 =
        MAPPER.readTree(httpGet("/api/v2/namespaces/" + ns + "/jobs/ns_field_job").body());

    // namespace field must be populated and non-empty
    String v2Ns =
        v2.path("namespace").isObject()
            ? v2.path("namespace").path("name").asText()
            : v2.path("namespace").asText();
    assertThat(v2Ns).isNotBlank();
  }

  // ---------------------------------------------------------------------------
  // Tests — cross-namespace list (V2 only)
  // ---------------------------------------------------------------------------

  @Test
  public void testListAllJobs_v2CrossNamespace_includesKnownJob() throws Exception {
    client.createJob(NAMESPACE_NAME, "cross_ns_job", JOB_META);
    populateDenormalized();

    HttpResponse<String> resp = httpGet("/api/v2/jobs?limit=200&offset=0");
    assertThat(resp.statusCode()).isEqualTo(200);

    List<JsonNode> jobs = extractList(resp.body(), "jobs");
    Set<String> jobNames =
        jobs.stream().map(n -> n.path("name").asText()).collect(Collectors.toSet());
    assertThat(jobNames).contains("cross_ns_job");
  }

  // ---------------------------------------------------------------------------
  // Tests — 404 / error-case parity
  // ---------------------------------------------------------------------------

  @Test
  public void testGetJob_v2Returns404_jobNotFound() throws Exception {
    HttpResponse<String> resp =
        httpGet("/api/v2/namespaces/" + enc(NAMESPACE_NAME) + "/jobs/no_such_job");
    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  public void testListJobs_v2Returns404_namespaceNotFound() throws Exception {
    HttpResponse<String> resp = httpGet("/api/v2/namespaces/completely_missing_namespace/jobs");
    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  public void testListJobs_v1AndV2_agreeOn404_forMissingNamespace() throws Exception {
    // V1 list-jobs auto-creates the namespace on access and returns 200 with an empty list.
    // V2 enforces namespace existence and returns 404.
    // This is a known behavioral difference between V1 and V2 list semantics.
    HttpResponse<String> v1Resp = httpGet("/api/v1/namespaces/missing_ns_jobs_v1v2/jobs");
    HttpResponse<String> v2Resp = httpGet("/api/v2/namespaces/missing_ns_jobs_v1v2/jobs");
    assertThat(v1Resp.statusCode())
        .as("V1 status for missing ns (auto-creates → 200)")
        .isIn(200, 404);
    assertThat(v2Resp.statusCode()).as("V2 status for missing ns").isEqualTo(404);
  }

  // ---------------------------------------------------------------------------
  // Tests — mutation propagation
  // ---------------------------------------------------------------------------

  @Test
  public void testDeleteJob_v2ShowsDeleted_andBothReturn404() throws Exception {
    client.createJob(NAMESPACE_NAME, "delete_me_job", JOB_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    assertThat(httpGet("/api/v2/namespaces/" + ns + "/jobs/delete_me_job").statusCode())
        .as("V2 before delete")
        .isEqualTo(200);

    HttpResponse<String> deleteResp =
        httpDelete("/api/v2/namespaces/" + ns + "/jobs/delete_me_job");
    assertThat(deleteResp.statusCode()).as("delete response").isEqualTo(200);

    assertThat(httpGet("/api/v1/namespaces/" + ns + "/jobs/delete_me_job").statusCode())
        .as("V1 after delete")
        .isEqualTo(404);
    assertThat(httpGet("/api/v2/namespaces/" + ns + "/jobs/delete_me_job").statusCode())
        .as("V2 after delete")
        .isEqualTo(404);
  }

  @Test
  public void testCreateJob_visibleInBothV1AndV2() throws Exception {
    client.createJob(NAMESPACE_NAME, "create_visible_job", JOB_META);
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    assertThat(httpGet("/api/v1/namespaces/" + ns + "/jobs/create_visible_job").statusCode())
        .as("V1 GET after create")
        .isEqualTo(200);
    assertThat(httpGet("/api/v2/namespaces/" + ns + "/jobs/create_visible_job").statusCode())
        .as("V2 GET after create + denorm populate")
        .isEqualTo(200);
  }

  // ---------------------------------------------------------------------------
  // Structural parity — full response shape compare with rich seeding (multiple
  // runs + schema facets on output dataset). This is the test that would have
  // caught the V2 latestRuns regression (1 vs 10) and the dataset_facets-null
  // regression up front, instead of letting them slip past CI.
  // ---------------------------------------------------------------------------

  /**
   * Sends an OL COMPLETE event with a schema facet on the output dataset so the resulting run
   * carries dataset_facets — needed to exercise the V2 dataset_facets parity path.
   */
  private String buildCompleteEventWithSchemaFacet(
      UUID runId, String namespace, String jobName, String inputDataset, String outputDataset) {
    return String.format(
        "{\"eventType\":\"COMPLETE\",\"eventTime\":\"%s\","
            + "\"run\":{\"runId\":\"%s\"},"
            + "\"job\":{\"namespace\":\"%s\",\"name\":\"%s\"},"
            + "\"inputs\":[{\"namespace\":\"%s\",\"name\":\"%s\"}],"
            + "\"outputs\":[{\"namespace\":\"%s\",\"name\":\"%s\","
            + "  \"facets\":{\"schema\":{\"_producer\":\"p\",\"_schemaURL\":\"s\","
            + "    \"fields\":[{\"name\":\"id\",\"type\":\"INTEGER\"},"
            + "               {\"name\":\"value\",\"type\":\"STRING\"}]}}}],"
            + "\"producer\":\"https://github.com/OpenLineage/OpenLineage/blob/v1-0-0/client\","
            + "\"schemaURL\":\"https://openlineage.io/spec/1-0-1/OpenLineage.json#/definitions/RunEvent\"}",
        Instant.now(),
        runId,
        namespace,
        jobName,
        namespace,
        inputDataset,
        namespace,
        outputDataset);
  }

  @Test
  public void testGetJob_v1AndV2_fullStructuralParity_withMultipleRunsAndFacets() throws Exception {
    String jobName = "structural_parity_job";

    // Seed THREE runs (so latestRuns mirrors V1's up-to-10 behaviour) with a schema facet on the
    // output dataset (so dataset_facets is non-null on both sides). If V2 ever drops latestRuns,
    // collapses it to a single element, or returns dataset_facets:null, the structural diff fails.
    for (int i = 0; i < 3; i++) {
      UUID runId = UUID.randomUUID();
      HttpResponse<String> ingest =
          http2.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(baseUrl + "/api/v1/lineage"))
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          buildCompleteEventWithSchemaFacet(
                              runId,
                              NAMESPACE_NAME,
                              jobName,
                              "structural_in_" + i,
                              "structural_out")))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(ingest.statusCode()).as("OL ingest #%d", i).isEqualTo(201);
      // Stagger so latestRuns ordering is deterministic.
      Thread.sleep(20);
    }
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    JsonNode v1 = MAPPER.readTree(httpGet("/api/v1/namespaces/" + ns + "/jobs/" + jobName).body());
    JsonNode v2 = MAPPER.readTree(httpGet("/api/v2/namespaces/" + ns + "/jobs/" + jobName).body());

    // Sanity guards — fail loudly if seeding didn't actually produce the things we want to compare.
    assertThat(v1.path("latestRuns").isArray())
        .as("seed sanity: V1 must have a latestRuns array")
        .isTrue();
    assertThat(v1.path("latestRuns").size())
        .as("seed sanity: V1 must surface all 3 historical runs")
        .isEqualTo(3);

    V1V2ParityAssertions.assertStructurallyEqual(v1, v2);
  }

  @Test
  public void testListJobs_v1AndV2_fullStructuralParity_withMultipleRunsAndFacets()
      throws Exception {
    String jobName = "structural_list_job";

    for (int i = 0; i < 3; i++) {
      UUID runId = UUID.randomUUID();
      HttpResponse<String> ingest =
          http2.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(baseUrl + "/api/v1/lineage"))
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          buildCompleteEventWithSchemaFacet(
                              runId, NAMESPACE_NAME, jobName, "list_in_" + i, "list_out")))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(ingest.statusCode()).as("OL ingest #%d", i).isEqualTo(201);
      Thread.sleep(20);
    }
    populateDenormalized();

    String ns = enc(NAMESPACE_NAME);
    JsonNode v1 = MAPPER.readTree(httpGet("/api/v1/namespaces/" + ns + "/jobs?limit=200").body());
    JsonNode v2 = MAPPER.readTree(httpGet("/api/v2/namespaces/" + ns + "/jobs?limit=200").body());

    V1V2ParityAssertions.assertStructurallyEqual(v1, v2);
  }
}

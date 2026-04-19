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
 * Parity regression tests: V1 and V3 job endpoints.
 *
 * <p>V3 is AGE-graph-backed and populated by {@code GraphWriter} on lineage event ingestion. V1
 * jobs and V3 jobs must agree on the set of jobs produced by OpenLineage events.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class JobResourceV1V3ParityIT extends BaseIntegrationTest {

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

  private void ingest(String ns, String job, String outDs) throws Exception {
    HttpResponse<String> resp = sendLineage(buildEvent(UUID.randomUUID(), ns, job, outDs)).join();
    assertThat(resp.statusCode()).isEqualTo(201);
  }

  @Test
  public void testGlobalListJobs_v1AndV3_containSameIngestedNames() throws Exception {
    String ns = "job_parity_ns";
    ingest(ns, "job_alpha", "ds_a");
    ingest(ns, "job_beta", "ds_b");
    ingest(ns, "job_gamma", "ds_c");

    HttpResponse<String> v1 = httpGet("/api/v1/jobs?limit=500");
    HttpResponse<String> v3 = httpGet("/api/v3/jobs?limit=500");
    assertThat(v1.statusCode()).isEqualTo(200);
    assertThat(v3.statusCode()).isEqualTo(200);

    Set<String> v1Names =
        extractList(v1.body(), "jobs").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    Set<String> v3Names =
        extractList(v3.body(), "jobs").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    assertThat(v1Names).contains("job_alpha", "job_beta", "job_gamma");
    assertThat(v3Names)
        .as("V3 AGE graph must contain each ingested job")
        .contains("job_alpha", "job_beta", "job_gamma");
  }

  @Test
  public void testNamespaceScopedListJobs_v1AndV3_agree() throws Exception {
    String ns = "ns_scoped_jobs";
    ingest(ns, "scoped_j1", "ds_1");
    ingest(ns, "scoped_j2", "ds_2");

    HttpResponse<String> v1 = httpGet("/api/v1/namespaces/" + enc(ns) + "/jobs?limit=500");
    HttpResponse<String> v3 = httpGet("/api/v3/namespaces/" + enc(ns) + "/jobs?limit=500");
    assertThat(v1.statusCode()).isEqualTo(200);
    assertThat(v3.statusCode()).isEqualTo(200);

    Set<String> v1Names =
        extractList(v1.body(), "jobs").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());
    Set<String> v3Names =
        extractList(v3.body(), "jobs").stream()
            .map(n -> n.path("name").asText())
            .collect(Collectors.toSet());

    assertThat(v1Names).contains("scoped_j1", "scoped_j2");
    assertThat(v3Names).contains("scoped_j1", "scoped_j2");
  }

  @Test
  public void testGetJob_v1AndV3_agreeOn200AndName() throws Exception {
    String ns = "get_job_parity_ns";
    ingest(ns, "get_me", "ds_get_me");

    HttpResponse<String> v1 = httpGet("/api/v1/namespaces/" + enc(ns) + "/jobs/" + enc("get_me"));
    HttpResponse<String> v3 = httpGet("/api/v3/namespaces/" + enc(ns) + "/jobs/" + enc("get_me"));
    assertThat(v1.statusCode()).isEqualTo(200);
    assertThat(v3.statusCode()).isEqualTo(200);

    assertThat(MAPPER.readTree(v1.body()).path("name").asText()).isEqualTo("get_me");
    assertThat(MAPPER.readTree(v3.body()).path("name").asText()).isEqualTo("get_me");
  }

  @Test
  public void testGetJob_v1AndV3_agreeOn404() throws Exception {
    String ns = "missing_job_ns";
    ingest(ns, "exists_here", "any_ds");

    HttpResponse<String> v1 =
        httpGet("/api/v1/namespaces/" + enc(ns) + "/jobs/" + enc("no_such_job"));
    HttpResponse<String> v3 =
        httpGet("/api/v3/namespaces/" + enc(ns) + "/jobs/" + enc("no_such_job"));
    assertThat(v1.statusCode()).isEqualTo(404);
    assertThat(v3.statusCode()).isEqualTo(404);
  }

  @Test
  public void testHttpsUriNamespaceJobs_v1AndV3_bothSee() throws Exception {
    String uriNs = "https://github.com/acme/v3-job-parity";
    ingest(uriNs, "uri_job_name", "uri_ds_name");

    HttpResponse<String> v1 =
        httpGet("/api/v1/namespaces/" + enc(uriNs) + "/jobs/" + enc("uri_job_name"));
    HttpResponse<String> v3 =
        httpGet("/api/v3/namespaces/" + enc(uriNs) + "/jobs/" + enc("uri_job_name"));
    assertThat(v1.statusCode()).as("V1 URI-ns job get").isEqualTo(200);
    assertThat(v3.statusCode()).as("V3 URI-ns job get").isEqualTo(200);
    assertThat(MAPPER.readTree(v3.body()).path("name").asText()).isEqualTo("uri_job_name");
  }
}

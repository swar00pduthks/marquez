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
import marquez.BaseIntegrationTest;
import marquez.MarquezApp;
import marquez.api.JdbiUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the Sources endpoint.
 *
 * <p>The SourceDao previously had a mapper registration issue (SourceRowMapper removed, causing 500
 * errors when calling sources endpoints). These tests verify the fix: both {@code SourceMapper}
 * (for read methods returning {@code Source}) and {@code SourceRowMapper} (for upsert methods
 * returning {@code SourceRow}) must be registered simultaneously — JDBI v3 resolves by declared
 * return type.
 *
 * <p>All sources endpoints must return 200, never 500.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class SourceResourceRegressionIT extends BaseIntegrationTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @BeforeEach
  public void setup() {
    createNamespace(NAMESPACE_NAME);
  }

  @AfterEach
  public void tearDown() {
    JdbiUtils.cleanDatabase(MarquezApp.getJdbiInstanceForTesting());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

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
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  public void testListSources_returns200_notInternalServerError() throws Exception {
    // Regression: this endpoint previously returned 500 due to missing SourceRowMapper
    HttpResponse<String> resp = httpGet("/api/v1/sources");
    assertThat(resp.statusCode()).as("GET /api/v1/sources must not return 500").isNotEqualTo(500);
    assertThat(resp.statusCode()).as("GET /api/v1/sources status").isEqualTo(200);
  }

  @Test
  public void testListSources_returns200_withExistingSources() throws Exception {
    // Create a source via the client, then verify the list endpoint returns 200
    createSource(DB_TABLE_SOURCE_NAME);

    HttpResponse<String> resp = httpGet("/api/v1/sources");
    assertThat(resp.statusCode()).isEqualTo(200);

    JsonNode root = MAPPER.readTree(resp.body());
    JsonNode sourcesNode = root.isArray() ? root : root.path("sources");
    assertThat(sourcesNode.isArray()).isTrue();
    assertThat(sourcesNode.size()).isGreaterThanOrEqualTo(1);
  }

  @Test
  public void testGetSource_returns200_forExistingSource() throws Exception {
    createSource(DB_TABLE_SOURCE_NAME);

    HttpResponse<String> resp = httpGet("/api/v1/sources/" + DB_TABLE_SOURCE_NAME);
    assertThat(resp.statusCode())
        .as("GET /api/v1/sources/{name} must not return 500")
        .isNotEqualTo(500);
    assertThat(resp.statusCode()).as("GET /api/v1/sources/{name} status").isEqualTo(200);

    JsonNode source = MAPPER.readTree(resp.body());
    assertThat(source.path("name").asText())
        .as("source name matches")
        .isEqualTo(DB_TABLE_SOURCE_NAME);
    assertThat(source.path("type").asText()).as("type populated").isNotBlank();
  }

  @Test
  public void testGetSource_returns404_forNonExistentSource() throws Exception {
    HttpResponse<String> resp = httpGet("/api/v1/sources/this_source_does_not_exist_xyz");
    assertThat(resp.statusCode()).as("non-existent source must return 404, not 500").isEqualTo(404);
  }

  @Test
  public void testCreateSource_viaPut_returns200() throws Exception {
    String sourceBody =
        String.format(
            "{\"type\":\"%s\",\"connectionUrl\":\"%s\",\"description\":\"%s\"}",
            DB_TABLE_SOURCE_TYPE, DB_TABLE_CONNECTION_URL.toString(), DB_TABLE_SOURCE_DESCRIPTION);

    HttpResponse<String> resp = httpPut("/api/v1/sources/regression_source", sourceBody);
    assertThat(resp.statusCode())
        .as("PUT /api/v1/sources/{name} must not return 500")
        .isNotEqualTo(500);
    assertThat(resp.statusCode()).as("PUT source status").isEqualTo(200);

    JsonNode source = MAPPER.readTree(resp.body());
    assertThat(source.path("name").asText()).isEqualTo("regression_source");
    assertThat(source.path("type").asText()).isNotBlank();
    assertThat(source.path("connectionUrl").asText()).isNotBlank();
  }

  @Test
  public void testCreateSource_thenList_sourceAppearsInListing() throws Exception {
    createSource("list_test_source");

    HttpResponse<String> listResp = httpGet("/api/v1/sources");
    assertThat(listResp.statusCode()).isEqualTo(200);

    JsonNode root = MAPPER.readTree(listResp.body());
    JsonNode sourcesNode = root.isArray() ? root : root.path("sources");

    boolean found = false;
    for (JsonNode s : sourcesNode) {
      if ("list_test_source".equals(s.path("name").asText())) {
        found = true;
        break;
      }
    }
    assertThat(found).as("created source appears in listing").isTrue();
  }

  @Test
  public void testSourceFields_allRequiredFieldsPopulated() throws Exception {
    createSource(DB_TABLE_SOURCE_NAME);

    HttpResponse<String> resp = httpGet("/api/v1/sources/" + DB_TABLE_SOURCE_NAME);
    assertThat(resp.statusCode()).isEqualTo(200);

    JsonNode source = MAPPER.readTree(resp.body());
    // Required fields for a valid Source response
    assertThat(source.path("name").asText()).as("name").isNotBlank();
    assertThat(source.path("type").asText()).as("type").isNotBlank();
    assertThat(source.path("createdAt").asText()).as("createdAt").isNotBlank();
    assertThat(source.path("updatedAt").asText()).as("updatedAt").isNotBlank();
    assertThat(source.path("connectionUrl").asText()).as("connectionUrl").isNotBlank();
  }
}

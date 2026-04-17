/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import marquez.BaseIntegrationTest;
import marquez.MarquezApp;
import marquez.api.JdbiUtils;
import marquez.client.Utils;
import marquez.client.models.Dataset;
import marquez.service.DenormalizedLineageService;
import marquez.service.PartitionManagementService;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for V2 Dataset API endpoints.
 *
 * <p>Tests the V2 API endpoints that use denormalized tables for performance.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class DatasetResourceV2IntegrationTest extends BaseIntegrationTest {

  @BeforeEach
  public void setup() {
    createNamespace(NAMESPACE_NAME);
    createSource(DB_TABLE_SOURCE_NAME);
  }

  @AfterEach
  public void tearDown() {
    Jdbi staticAppJdbi = MarquezApp.getJdbiInstanceForTesting();
    JdbiUtils.cleanDatabase(staticAppJdbi);
  }

  private void populateDenormalizedForNamespace(String namespaceName) {
    Jdbi staticAppJdbi = MarquezApp.getJdbiInstanceForTesting();
    PartitionManagementService partitionManagementService =
        new PartitionManagementService(staticAppJdbi, 10, 12);
    DenormalizedLineageService denormalizedLineageService =
        new DenormalizedLineageService(staticAppJdbi, partitionManagementService);
    denormalizedLineageService.populateAllDenormalizedEntities();
  }

  @Test
  public void testApp_v2_listDatasets() throws Exception {
    // Create datasets using V1 API
    client.createDataset(NAMESPACE_NAME, "v2_test_dataset_1", DB_TABLE_META);
    client.createDataset(NAMESPACE_NAME, "v2_test_dataset_2", DB_TABLE_META);
    populateDenormalizedForNamespace(NAMESPACE_NAME);

    // Query V2 API endpoint
    String encodedNamespace = URLEncoder.encode(NAMESPACE_NAME, StandardCharsets.UTF_8);
    URI uri = new URI(baseUrl + "/api/v2/namespaces/" + encodedNamespace + "/datasets");
    HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
    HttpResponse<String> response = http2.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);

    List<Dataset> datasets = Utils.fromJson(response.body(), new TypeReference<List<Dataset>>() {});
    assertThat(datasets).hasSizeGreaterThanOrEqualTo(2);

    List<String> datasetNames = datasets.stream().map(Dataset::getName).toList();
    assertThat(datasetNames).contains("v2_test_dataset_1", "v2_test_dataset_2");
  }

  @Test
  public void testApp_v2_listDatasetsWithPagination() throws Exception {
    // Create 10 datasets
    for (int i = 0; i < 10; i++) {
      client.createDataset(NAMESPACE_NAME, "pagination_dataset_" + i, DB_TABLE_META);
    }
    populateDenormalizedForNamespace(NAMESPACE_NAME);

    // First page
    String encodedNamespace = URLEncoder.encode(NAMESPACE_NAME, StandardCharsets.UTF_8);
    URI uri1 =
        new URI(baseUrl + "/api/v2/namespaces/" + encodedNamespace + "/datasets?limit=5&offset=0");
    HttpRequest request1 = HttpRequest.newBuilder().uri(uri1).GET().build();
    HttpResponse<String> response1 = http2.send(request1, HttpResponse.BodyHandlers.ofString());

    assertThat(response1.statusCode()).isEqualTo(200);
    List<Dataset> page1 = Utils.fromJson(response1.body(), new TypeReference<List<Dataset>>() {});
    assertThat(page1).hasSize(5);

    // Second page
    URI uri2 =
        new URI(baseUrl + "/api/v2/namespaces/" + encodedNamespace + "/datasets?limit=5&offset=5");
    HttpRequest request2 = HttpRequest.newBuilder().uri(uri2).GET().build();
    HttpResponse<String> response2 = http2.send(request2, HttpResponse.BodyHandlers.ofString());

    assertThat(response2.statusCode()).isEqualTo(200);
    List<Dataset> page2 = Utils.fromJson(response2.body(), new TypeReference<List<Dataset>>() {});
    assertThat(page2).hasSize(5);

    // Verify no overlap
    List<String> page1Names = page1.stream().map(Dataset::getName).toList();
    List<String> page2Names = page2.stream().map(Dataset::getName).toList();
    assertThat(page1Names).doesNotContainAnyElementsOf(page2Names);
  }

  @Test
  public void testApp_v2_getDataset() throws Exception {
    client.createDataset(NAMESPACE_NAME, "specific_v2_dataset", DB_TABLE_META);
    populateDenormalizedForNamespace(NAMESPACE_NAME);

    String encodedNamespace = URLEncoder.encode(NAMESPACE_NAME, StandardCharsets.UTF_8);
    URI uri =
        new URI(
            baseUrl + "/api/v2/namespaces/" + encodedNamespace + "/datasets/specific_v2_dataset");
    HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
    HttpResponse<String> response = http2.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);

    Dataset dataset = Utils.fromJson(response.body(), new TypeReference<Dataset>() {});
    assertThat(dataset).isNotNull();
    assertThat(dataset.getName()).isEqualTo("specific_v2_dataset");
  }

  @Test
  public void testApp_v2_getDatasetNotFound() throws Exception {
    createNamespace("test_namespace");

    URI uri = new URI(baseUrl + "/api/v2/namespaces/test_namespace/datasets/non_existent_dataset");
    HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
    HttpResponse<String> response = http2.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  public void testApp_v2_listDatasetsNonExistentNamespace() throws Exception {
    URI uri = new URI(baseUrl + "/api/v2/namespaces/non_existent_namespace/datasets");
    HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
    HttpResponse<String> response = http2.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  public void testApp_v2_listDatasetsOrderedByName() throws Exception {
    // Create datasets and then set deterministic updated_at timestamps.
    client.createDataset(NAMESPACE_NAME, "oldest_dataset", DB_TABLE_META);
    client.createDataset(NAMESPACE_NAME, "middle_dataset", DB_TABLE_META);
    client.createDataset(NAMESPACE_NAME, "newest_dataset", DB_TABLE_META);

    Jdbi staticAppJdbi = MarquezApp.getJdbiInstanceForTesting();
    Instant now = Instant.now();
    staticAppJdbi.useHandle(
        handle -> {
          handle
              .createUpdate(
                  "UPDATE datasets SET updated_at = :updatedAt WHERE namespace_name = :namespace AND name = :name")
              .bind("updatedAt", now.minusSeconds(120))
              .bind("namespace", NAMESPACE_NAME)
              .bind("name", "oldest_dataset")
              .execute();
          handle
              .createUpdate(
                  "UPDATE datasets SET updated_at = :updatedAt WHERE namespace_name = :namespace AND name = :name")
              .bind("updatedAt", now.minusSeconds(60))
              .bind("namespace", NAMESPACE_NAME)
              .bind("name", "middle_dataset")
              .execute();
          handle
              .createUpdate(
                  "UPDATE datasets SET updated_at = :updatedAt WHERE namespace_name = :namespace AND name = :name")
              .bind("updatedAt", now)
              .bind("namespace", NAMESPACE_NAME)
              .bind("name", "newest_dataset")
              .execute();
        });

    populateDenormalizedForNamespace(NAMESPACE_NAME);

    String encodedNamespace = URLEncoder.encode(NAMESPACE_NAME, StandardCharsets.UTF_8);
    URI uri = new URI(baseUrl + "/api/v2/namespaces/" + encodedNamespace + "/datasets");
    HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
    HttpResponse<String> response = http2.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    List<Dataset> datasets = Utils.fromJson(response.body(), new TypeReference<List<Dataset>>() {});

    // V2 should return datasets ordered by name.
    assertThat(datasets).isNotEmpty();
    List<String> datasetNames = datasets.stream().map(Dataset::getName).toList();
    assertThat(datasetNames).contains("oldest_dataset", "middle_dataset", "newest_dataset");

    for (int i = 1; i < datasets.size(); i++) {
      assertThat(datasets.get(i - 1).getName().compareTo(datasets.get(i).getName()))
          .isLessThanOrEqualTo(0);
    }
  }

  @Test
  public void testApp_v2_getDatasetWithFields() throws Exception {
    // DB_TABLE_META includes field definitions
    client.createDataset(NAMESPACE_NAME, "dataset_with_fields", DB_TABLE_META);
    populateDenormalizedForNamespace(NAMESPACE_NAME);

    String encodedNamespace = URLEncoder.encode(NAMESPACE_NAME, StandardCharsets.UTF_8);
    URI uri =
        new URI(
            baseUrl + "/api/v2/namespaces/" + encodedNamespace + "/datasets/dataset_with_fields");
    HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
    HttpResponse<String> response = http2.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);

    Dataset dataset = Utils.fromJson(response.body(), new TypeReference<Dataset>() {});
    assertThat(dataset).isNotNull();
    assertThat(dataset.getFields()).isNotNull().isNotEmpty();
  }
}

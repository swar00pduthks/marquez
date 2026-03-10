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
import java.util.List;
import marquez.BaseIntegrationTest;
import marquez.MarquezApp;
import marquez.api.JdbiUtils;
import marquez.client.Utils;
import marquez.client.models.Job;
import marquez.service.DenormalizedLineageService;
import marquez.service.PartitionManagementService;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for V2 Job API endpoints.
 *
 * <p>Tests the V2 API endpoints that use denormalized tables for performance.
 */
@org.junit.jupiter.api.Tag("IntegrationTests")
public class JobResourceV2IntegrationTest extends BaseIntegrationTest {

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
  public void testApp_v2_listJobs() throws Exception {
    // Create jobs using V1 API (which populates denormalized tables)
    client.createJob(NAMESPACE_NAME, "v2_test_job_1", JOB_META);
    client.createJob(NAMESPACE_NAME, "v2_test_job_2", JOB_META);
    populateDenormalizedForNamespace(NAMESPACE_NAME);

    // Query V2 API endpoint
    String encodedNamespace = URLEncoder.encode(NAMESPACE_NAME, StandardCharsets.UTF_8);
    URI uri = new URI(baseUrl + "/api/v2/namespaces/" + encodedNamespace + "/jobs");
    HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
    HttpResponse<String> response = http2.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);

    List<Job> jobs = Utils.fromJson(response.body(), new TypeReference<List<Job>>() {});
    assertThat(jobs).hasSizeGreaterThanOrEqualTo(2);

    List<String> jobNames = jobs.stream().map(j -> j.getName()).toList();
    assertThat(jobNames).contains("v2_test_job_1", "v2_test_job_2");
  }

  @Test
  public void testApp_v2_listJobsWithPagination() throws Exception {
    // Create 10 jobs
    for (int i = 0; i < 10; i++) {
      client.createJob(NAMESPACE_NAME, "pagination_job_" + i, JOB_META);
    }
    populateDenormalizedForNamespace(NAMESPACE_NAME);

    // First page
    String encodedNamespace = URLEncoder.encode(NAMESPACE_NAME, StandardCharsets.UTF_8);
    URI uri1 =
        new URI(baseUrl + "/api/v2/namespaces/" + encodedNamespace + "/jobs?limit=5&offset=0");
    HttpRequest request1 = HttpRequest.newBuilder().uri(uri1).GET().build();
    HttpResponse<String> response1 = http2.send(request1, HttpResponse.BodyHandlers.ofString());

    assertThat(response1.statusCode()).isEqualTo(200);
    List<Job> page1 = Utils.fromJson(response1.body(), new TypeReference<List<Job>>() {});
    assertThat(page1).hasSize(5);

    // Second page
    URI uri2 =
        new URI(baseUrl + "/api/v2/namespaces/" + encodedNamespace + "/jobs?limit=5&offset=5");
    HttpRequest request2 = HttpRequest.newBuilder().uri(uri2).GET().build();
    HttpResponse<String> response2 = http2.send(request2, HttpResponse.BodyHandlers.ofString());

    assertThat(response2.statusCode()).isEqualTo(200);
    List<Job> page2 = Utils.fromJson(response2.body(), new TypeReference<List<Job>>() {});
    assertThat(page2).hasSize(5);

    // Verify no overlap
    List<String> page1Names = page1.stream().map(Job::getName).toList();
    List<String> page2Names = page2.stream().map(Job::getName).toList();
    assertThat(page1Names).doesNotContainAnyElementsOf(page2Names);
  }

  @Test
  public void testApp_v2_getJob() throws Exception {
    client.createJob(NAMESPACE_NAME, "specific_v2_job", JOB_META);
    populateDenormalizedForNamespace(NAMESPACE_NAME);

    String encodedNamespace = URLEncoder.encode(NAMESPACE_NAME, StandardCharsets.UTF_8);
    URI uri = new URI(baseUrl + "/api/v2/namespaces/" + encodedNamespace + "/jobs/specific_v2_job");
    HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
    HttpResponse<String> response = http2.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);

    Job job = Utils.fromJson(response.body(), new TypeReference<Job>() {});
    assertThat(job).isNotNull();
    assertThat(job.getName()).isEqualTo("specific_v2_job");
  }

  @Test
  public void testApp_v2_getJobNotFound() throws Exception {
    createNamespace("test_namespace");

    URI uri = new URI(baseUrl + "/api/v2/namespaces/test_namespace/jobs/non_existent_job");
    HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
    HttpResponse<String> response = http2.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  public void testApp_v2_listJobsNonExistentNamespace() throws Exception {
    URI uri = new URI(baseUrl + "/api/v2/namespaces/non_existent_namespace/jobs");
    HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
    HttpResponse<String> response = http2.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  public void testApp_v2_listJobsOrderedByUpdatedAt() throws Exception {
    // Create jobs with delays to ensure different timestamps
    client.createJob(NAMESPACE_NAME, "oldest_job", JOB_META);
    Thread.sleep(100);
    client.createJob(NAMESPACE_NAME, "middle_job", JOB_META);
    Thread.sleep(100);
    client.createJob(NAMESPACE_NAME, "newest_job", JOB_META);
    populateDenormalizedForNamespace(NAMESPACE_NAME);

    String encodedNamespace = URLEncoder.encode(NAMESPACE_NAME, StandardCharsets.UTF_8);
    URI uri = new URI(baseUrl + "/api/v2/namespaces/" + encodedNamespace + "/jobs");
    HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
    HttpResponse<String> response = http2.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    List<Job> jobs = Utils.fromJson(response.body(), new TypeReference<List<Job>>() {});

    // V2 should return jobs ordered by updated_at DESC (newest first)
    assertThat(jobs).isNotEmpty();
    assertThat(jobs.get(0).getName()).isEqualTo("newest_job");
  }
}

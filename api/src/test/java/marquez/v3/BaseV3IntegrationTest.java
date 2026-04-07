/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.CompletableFuture;
import marquez.BaseIntegrationTest;

/**
 * Base class for V3 Graph API integration tests.
 *
 * <p>Extends {@link BaseIntegrationTest} (which starts a full {@code MarquezApp} backed by an
 * {@code apache/age:latest} Testcontainer) and adds helpers for every V3 HTTP endpoint.
 *
 * <p>Because {@link marquez.PostgresContainer} already uses {@code apache/age:latest}, AGE is
 * available in all tests and the full V3 resource set is registered automatically.
 *
 * <h2>Endpoint helpers</h2>
 *
 * <ul>
 *   <li>{@link #postV3Lineage(String)} – {@code POST /api/v3/lineage}
 *   <li>{@link #getV3Lineage(String, int, boolean)} – {@code GET /api/v3/lineage}
 *   <li>{@link #getV3Namespaces()} – {@code GET /api/v3/namespaces}
 *   <li>{@link #getV3Namespace(String)} – {@code GET /api/v3/namespaces/{ns}}
 *   <li>{@link #getV3Jobs(String)} – {@code GET /api/v3/namespaces/{ns}/jobs}
 *   <li>{@link #getV3Job(String, String)} – {@code GET /api/v3/namespaces/{ns}/jobs/{job}}
 *   <li>{@link #getV3JobRuns(String, String)} – {@code GET /api/v3/namespaces/{ns}/jobs/{job}/runs}
 *   <li>{@link #getV3Datasets(String)} – {@code GET /api/v3/namespaces/{ns}/datasets}
 *   <li>{@link #getV3Dataset(String, String)} – {@code GET /api/v3/namespaces/{ns}/datasets/{ds}}
 * </ul>
 */
public abstract class BaseV3IntegrationTest extends BaseIntegrationTest {

  // ---------------------------------------------------------------------------
  // Lineage ingestion
  // ---------------------------------------------------------------------------

  /** POSTs a raw JSON body to {@code POST /api/v3/lineage}. */
  protected CompletableFuture<HttpResponse<String>> postV3Lineage(String body) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/v3/lineage"))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(body))
            .build();
    return http2.sendAsync(request, BodyHandlers.ofString());
  }

  // ---------------------------------------------------------------------------
  // Lineage query
  // ---------------------------------------------------------------------------

  /**
   * GETs {@code /api/v3/lineage?nodeId=&depth=&aggregateToParentRun=}.
   *
   * @param nodeId format: {@code job:ns:name}, {@code dataset:ns:name}, or {@code run:runId}
   * @param depth number of hops (V1/V2 parity; default 2)
   * @param aggregateToParentRun when {@code true} aggregates child run states (V1/V2 parity)
   */
  protected CompletableFuture<HttpResponse<String>> getV3Lineage(
      String nodeId, int depth, boolean aggregateToParentRun) {
    String url =
        String.format(
            "%s/api/v3/lineage?nodeId=%s&depth=%d&aggregateToParentRun=%b",
            baseUrl, nodeId, depth, aggregateToParentRun);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .GET()
            .build();
    return http2.sendAsync(request, BodyHandlers.ofString());
  }

  /** GETs lineage with defaults (depth=2, aggregateToParentRun=false). */
  protected CompletableFuture<HttpResponse<String>> getV3Lineage(String nodeId) {
    return getV3Lineage(nodeId, 2, false);
  }

  // ---------------------------------------------------------------------------
  // Namespaces
  // ---------------------------------------------------------------------------

  /** GETs {@code /api/v3/namespaces}. */
  protected CompletableFuture<HttpResponse<String>> getV3Namespaces() {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/v3/namespaces"))
            .header("Accept", "application/json")
            .GET()
            .build();
    return http2.sendAsync(request, BodyHandlers.ofString());
  }

  /** GETs {@code /api/v3/namespaces/{namespace}}. */
  protected CompletableFuture<HttpResponse<String>> getV3Namespace(String namespace) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/v3/namespaces/" + encode(namespace)))
            .header("Accept", "application/json")
            .GET()
            .build();
    return http2.sendAsync(request, BodyHandlers.ofString());
  }

  // ---------------------------------------------------------------------------
  // Jobs
  // ---------------------------------------------------------------------------

  /** GETs {@code /api/v3/namespaces/{namespace}/jobs}. */
  protected CompletableFuture<HttpResponse<String>> getV3Jobs(String namespace) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/v3/namespaces/" + encode(namespace) + "/jobs"))
            .header("Accept", "application/json")
            .GET()
            .build();
    return http2.sendAsync(request, BodyHandlers.ofString());
  }

  /** GETs {@code /api/v3/namespaces/{namespace}/jobs/{job}}. */
  protected CompletableFuture<HttpResponse<String>> getV3Job(String namespace, String job) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    baseUrl + "/api/v3/namespaces/" + encode(namespace) + "/jobs/" + encode(job)))
            .header("Accept", "application/json")
            .GET()
            .build();
    return http2.sendAsync(request, BodyHandlers.ofString());
  }

  /** GETs {@code /api/v3/namespaces/{namespace}/jobs/{job}/runs}. */
  protected CompletableFuture<HttpResponse<String>> getV3JobRuns(String namespace, String job) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    baseUrl
                        + "/api/v3/namespaces/"
                        + encode(namespace)
                        + "/jobs/"
                        + encode(job)
                        + "/runs"))
            .header("Accept", "application/json")
            .GET()
            .build();
    return http2.sendAsync(request, BodyHandlers.ofString());
  }

  // ---------------------------------------------------------------------------
  // Datasets
  // ---------------------------------------------------------------------------

  /** GETs {@code /api/v3/namespaces/{namespace}/datasets}. */
  protected CompletableFuture<HttpResponse<String>> getV3Datasets(String namespace) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/v3/namespaces/" + encode(namespace) + "/datasets"))
            .header("Accept", "application/json")
            .GET()
            .build();
    return http2.sendAsync(request, BodyHandlers.ofString());
  }

  /** GETs {@code /api/v3/namespaces/{namespace}/datasets/{dataset}}. */
  protected CompletableFuture<HttpResponse<String>> getV3Dataset(String namespace, String dataset) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    baseUrl
                        + "/api/v3/namespaces/"
                        + encode(namespace)
                        + "/datasets/"
                        + encode(dataset)))
            .header("Accept", "application/json")
            .GET()
            .build();
    return http2.sendAsync(request, BodyHandlers.ofString());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** URL-encodes a path segment (replaces spaces and colons). */
  private static String encode(String segment) {
    return segment.replace(" ", "%20").replace(":", "%3A");
  }

  /**
   * Builds a minimal OpenLineage RunEvent JSON string suitable for use in tests.
   *
   * @param namespace job namespace
   * @param jobName job name
   * @param runId UUID string for the run
   * @param eventType START | RUNNING | COMPLETE | FAIL | ABORT
   * @param inputDatasets array of {@code "namespace:name"} dataset qualifiers (may be empty)
   * @param outputDatasets array of {@code "namespace:name"} dataset qualifiers (may be empty)
   */
  protected static String lineageEvent(
      String namespace,
      String jobName,
      String runId,
      String eventType,
      String[] inputDatasets,
      String[] outputDatasets) {

    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"eventType\":\"").append(eventType).append("\",");
    sb.append("\"eventTime\":\"2026-01-15T10:00:00Z\",");
    sb.append("\"producer\":\"https://github.com/OpenLineage/OpenLineage/test\",");
    sb.append("\"schemaURL\":\"https://openlineage.io/spec/1-0-5/OpenLineage.json\",");
    sb.append("\"run\":{\"runId\":\"").append(runId).append("\"},");
    sb.append("\"job\":{\"namespace\":\"").append(namespace).append("\",");
    sb.append("\"name\":\"").append(jobName).append("\"},");

    // Inputs
    sb.append("\"inputs\":[");
    for (int i = 0; i < inputDatasets.length; i++) {
      String[] parts = inputDatasets[i].split(":", 2);
      sb.append("{\"namespace\":\"").append(parts[0]).append("\",");
      sb.append("\"name\":\"").append(parts[1]).append("\",");
      sb.append("\"facets\":{\"schema\":{\"_producer\":\"test\",\"_schemaURL\":\"test\",");
      sb.append("\"fields\":[{\"name\":\"id\",\"type\":\"LONG\"}]}}}");
      if (i < inputDatasets.length - 1) sb.append(",");
    }
    sb.append("],");

    // Outputs
    sb.append("\"outputs\":[");
    for (int i = 0; i < outputDatasets.length; i++) {
      String[] parts = outputDatasets[i].split(":", 2);
      sb.append("{\"namespace\":\"").append(parts[0]).append("\",");
      sb.append("\"name\":\"").append(parts[1]).append("\",");
      sb.append("\"facets\":{\"schema\":{\"_producer\":\"test\",\"_schemaURL\":\"test\",");
      sb.append(
          "\"fields\":[{\"name\":\"id\",\"type\":\"LONG\"},{\"name\":\"ts\",\"type\":\"TIMESTAMP\"}]}}}");
      if (i < outputDatasets.length - 1) sb.append(",");
    }
    sb.append("]}");

    return sb.toString();
  }

  /**
   * Builds a RunEvent where the run is a child of {@code parentRunId}. The {@code parentJob} fields
   * are used in the {@code parent} facet.
   */
  protected static String childLineageEvent(
      String namespace,
      String jobName,
      String runId,
      String eventType,
      String parentRunId,
      String parentJobNamespace,
      String parentJobName,
      String[] inputDatasets,
      String[] outputDatasets) {

    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"eventType\":\"").append(eventType).append("\",");
    sb.append("\"eventTime\":\"2026-01-15T10:01:00Z\",");
    sb.append("\"producer\":\"https://github.com/OpenLineage/OpenLineage/test\",");
    sb.append("\"schemaURL\":\"https://openlineage.io/spec/1-0-5/OpenLineage.json\",");
    sb.append("\"run\":{\"runId\":\"").append(runId).append("\",");
    sb.append("\"facets\":{\"parent\":{\"_producer\":\"test\",\"_schemaURL\":\"test\",");
    sb.append("\"run\":{\"runId\":\"").append(parentRunId).append("\"},");
    sb.append("\"job\":{\"namespace\":\"").append(parentJobNamespace).append("\",");
    sb.append("\"name\":\"").append(parentJobName).append("\"}}}},");
    sb.append("\"job\":{\"namespace\":\"").append(namespace).append("\",");
    sb.append("\"name\":\"").append(jobName).append("\"},");

    sb.append("\"inputs\":[");
    for (int i = 0; i < inputDatasets.length; i++) {
      String[] parts = inputDatasets[i].split(":", 2);
      sb.append("{\"namespace\":\"").append(parts[0]).append("\",");
      sb.append("\"name\":\"").append(parts[1]).append("\"}");
      if (i < inputDatasets.length - 1) sb.append(",");
    }
    sb.append("],");

    sb.append("\"outputs\":[");
    for (int i = 0; i < outputDatasets.length; i++) {
      String[] parts = outputDatasets[i].split(":", 2);
      sb.append("{\"namespace\":\"").append(parts[0]).append("\",");
      sb.append("\"name\":\"").append(parts[1]).append("\"}");
      if (i < outputDatasets.length - 1) sb.append(",");
    }
    sb.append("]}");

    return sb.toString();
  }
}

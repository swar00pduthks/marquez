/*
 * Copyright 2018-2026 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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

@org.junit.jupiter.api.Tag("IntegrationTests")
public class LineageResourceV2IntegrationTest extends BaseIntegrationTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  public void setup() {
    createNamespace(NAMESPACE_NAME);
  }

  @AfterEach
  public void tearDown() {
    JdbiUtils.cleanDatabase(MarquezApp.getJdbiInstanceForTesting());
  }

  @Test
  public void testApp_v2_getLineageForJob() throws Exception {
    String jobName = "v2_lineage_job";
    String inputDataset = "v2_input_dataset";
    String outputDataset = "v2_output_dataset";
    UUID runId = UUID.randomUUID();

    HttpResponse<String> ingestionResponse =
        sendLineage(buildCompleteEvent(runId, jobName, inputDataset, outputDataset)).join();
    assertThat(ingestionResponse.statusCode()).isEqualTo(201);

    populateDenormalized(runId);

    String nodeId = "job:" + NAMESPACE_NAME + ":" + jobName;
    HttpResponse<String> response = fetchLineageV2(nodeId, 2).join();

    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode graph = mapper.readTree(response.body()).path("graph");
    assertThat(graph.isArray()).isTrue();
    assertThat(graph).hasSizeGreaterThanOrEqualTo(3);

    assertThat(graph.findValuesAsText("id"))
        .contains(
            nodeId,
            "dataset:" + NAMESPACE_NAME + ":" + inputDataset,
            "dataset:" + NAMESPACE_NAME + ":" + outputDataset);
  }

  @Test
  public void testApp_v2_getLineageForJobHonorsDepth() throws Exception {
    UUID runA = UUID.randomUUID();
    UUID runB = UUID.randomUUID();
    UUID runC = UUID.randomUUID();

    String jobA = "v2_depth_job_a";
    String jobB = "v2_depth_job_b";
    String jobC = "v2_depth_job_c";

    String dataset0 = "v2_depth_dataset_0";
    String dataset1 = "v2_depth_dataset_1";
    String dataset2 = "v2_depth_dataset_2";
    String dataset3 = "v2_depth_dataset_3";

    assertThat(sendLineage(buildCompleteEvent(runA, jobA, dataset0, dataset1)).join().statusCode())
        .isEqualTo(201);
    assertThat(sendLineage(buildCompleteEvent(runB, jobB, dataset1, dataset2)).join().statusCode())
        .isEqualTo(201);
    assertThat(sendLineage(buildCompleteEvent(runC, jobC, dataset2, dataset3)).join().statusCode())
        .isEqualTo(201);

    populateDenormalized(runA);
    populateDenormalized(runB);
    populateDenormalized(runC);

    String nodeId = "job:" + NAMESPACE_NAME + ":" + jobB;
    HttpResponse<String> depth0Response = fetchLineageV2(nodeId, 0).join();
    HttpResponse<String> depth1Response = fetchLineageV2(nodeId, 1).join();

    assertThat(depth0Response.statusCode()).isEqualTo(200);
    assertThat(depth1Response.statusCode()).isEqualTo(200);

    Set<String> depth0Ids = graphNodeIds(depth0Response.body());
    Set<String> depth1Ids = graphNodeIds(depth1Response.body());

    assertThat(depth0Ids)
        .contains(
            "job:" + NAMESPACE_NAME + ":" + jobB,
            "dataset:" + NAMESPACE_NAME + ":" + dataset1,
            "dataset:" + NAMESPACE_NAME + ":" + dataset2)
        .doesNotContain(
            "job:" + NAMESPACE_NAME + ":" + jobA,
            "job:" + NAMESPACE_NAME + ":" + jobC,
            "dataset:" + NAMESPACE_NAME + ":" + dataset0,
            "dataset:" + NAMESPACE_NAME + ":" + dataset3);

    assertThat(depth1Ids)
        .contains(
            "job:" + NAMESPACE_NAME + ":" + jobA,
            "job:" + NAMESPACE_NAME + ":" + jobB,
            "job:" + NAMESPACE_NAME + ":" + jobC,
            "dataset:" + NAMESPACE_NAME + ":" + dataset0,
            "dataset:" + NAMESPACE_NAME + ":" + dataset1,
            "dataset:" + NAMESPACE_NAME + ":" + dataset2,
            "dataset:" + NAMESPACE_NAME + ":" + dataset3);
  }

  @Test
  public void testApp_v2_getLineageForMissingJobReturns404() {
    HttpResponse<String> response =
        fetchLineageV2("job:" + NAMESPACE_NAME + ":missing-job", 2).join();

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  public void testApp_v2_getLineageForRunNode() throws Exception {
    String jobName = "v2_run_lineage_job";
    String inputDataset = "v2_run_input_dataset";
    String outputDataset = "v2_run_output_dataset";
    UUID runId = UUID.randomUUID();

    HttpResponse<String> ingestionResponse =
        sendLineage(buildCompleteEvent(runId, jobName, inputDataset, outputDataset)).join();
    assertThat(ingestionResponse.statusCode()).isEqualTo(201);

    populateDenormalized(runId);

    String nodeId = "run:" + runId;
    HttpResponse<String> response = fetchLineageV2WithParams(nodeId, 2, false, "");
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode graph = mapper.readTree(response.body()).path("graph");
    assertThat(graph.isArray()).isTrue();
    assertThat(graph.findValuesAsText("id")).contains(nodeId);

    HttpResponse<String> filteredFacetsResponse =
        fetchLineageV2WithParams(nodeId, 2, true, "spark");
    assertThat(filteredFacetsResponse.statusCode()).isEqualTo(200);
  }

  @Test
  public void testApp_v2_getLineageForDatasetVersionNode() throws Exception {
    String jobName = "v2_dataset_version_lineage_job";
    String inputDataset = "v2_dataset_version_input_dataset";
    String outputDataset = "v2_dataset_version_output_dataset";
    UUID runId = UUID.randomUUID();

    HttpResponse<String> ingestionResponse =
        sendLineage(buildCompleteEvent(runId, jobName, inputDataset, outputDataset)).join();
    assertThat(ingestionResponse.statusCode()).isEqualTo(201);

    populateDenormalized(runId);

    Optional<UUID> datasetVersionUuid =
        MarquezApp.getJdbiInstanceForTesting()
            .withHandle(
                handle ->
                    handle
                        .createQuery(
                            """
                            SELECT uuid
                            FROM dataset_versions
                            WHERE run_uuid = :runUuid
                              AND namespace_name = :namespaceName
                              AND dataset_name = :datasetName
                            ORDER BY created_at DESC
                            LIMIT 1
                            """)
                        .bind("runUuid", runId)
                        .bind("namespaceName", NAMESPACE_NAME)
                        .bind("datasetName", outputDataset)
                        .mapTo(UUID.class)
                        .findOne());

    assertThat(datasetVersionUuid).isPresent();

    String nodeId =
        "dataset:" + NAMESPACE_NAME + ":" + outputDataset + "#" + datasetVersionUuid.get();
    HttpResponse<String> response = fetchLineageV2WithParams(nodeId, 2, false, "");
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode graph = mapper.readTree(response.body()).path("graph");
    assertThat(graph.isArray()).isTrue();
    assertThat(graph.findValuesAsText("id")).contains(nodeId);
  }

  @Test
  public void testApp_v1AndV2_lineageParityForAllSupportedNodeTypes() throws Exception {
    String jobName = "v1_v2_parity_job";
    String inputDataset = "v1_v2_parity_input_dataset";
    String outputDataset = "v1_v2_parity_output_dataset";
    UUID runId = UUID.randomUUID();

    HttpResponse<String> ingestionResponse =
        sendLineage(buildCompleteEvent(runId, jobName, inputDataset, outputDataset)).join();
    assertThat(ingestionResponse.statusCode()).isEqualTo(201);

    populateDenormalized(runId);

    Optional<UUID> datasetVersionUuid =
        MarquezApp.getJdbiInstanceForTesting()
            .withHandle(
                handle ->
                    handle
                        .createQuery(
                            """
                            SELECT uuid
                            FROM dataset_versions
                            WHERE run_uuid = :runUuid
                              AND namespace_name = :namespaceName
                              AND dataset_name = :datasetName
                            ORDER BY created_at DESC
                            LIMIT 1
                            """)
                        .bind("runUuid", runId)
                        .bind("namespaceName", NAMESPACE_NAME)
                        .bind("datasetName", outputDataset)
                        .mapTo(UUID.class)
                        .findOne());

    assertThat(datasetVersionUuid).isPresent();

    assertLineageParity("job:" + NAMESPACE_NAME + ":" + jobName, 2, false, "");
    assertLineageParity("dataset:" + NAMESPACE_NAME + ":" + outputDataset, 2, false, "");
    assertLineageParity("run:" + runId, 2, false, "");
    assertLineageParity(
        "dataset:" + NAMESPACE_NAME + ":" + outputDataset + "#" + datasetVersionUuid.get(),
        2,
        false,
        "");
  }

  @Test
  public void testApp_v1AndV2_parentRunLineageParity() throws Exception {
    UUID parentRunId = UUID.randomUUID();
    UUID childRunId = UUID.randomUUID();
    String parentJob = "v1_v2_parent_job";
    String childJob = "v1_v2_child_job";
    String sharedDataset = "v1_v2_parent_output";
    String childOutput = "v1_v2_child_output";

    assertThat(
            sendLineage(buildCompleteEvent(parentRunId, parentJob, "seed_input", sharedDataset))
                .join()
                .statusCode())
        .isEqualTo(201);
    assertThat(
            sendLineage(buildCompleteEvent(childRunId, childJob, sharedDataset, childOutput))
                .join()
                .statusCode())
        .isEqualTo(201);

    MarquezApp.getJdbiInstanceForTesting()
        .useHandle(
            handle ->
                handle.execute(
                    "UPDATE runs SET parent_run_uuid = ? WHERE uuid = ?", parentRunId, childRunId));

    populateDenormalized(childRunId);
    populateDenormalized(parentRunId);

    assertLineageParity("run:" + parentRunId, 2, true, "");
  }

  private void populateDenormalized(UUID runId) {
    Jdbi jdbi = MarquezApp.getJdbiInstanceForTesting();
    PartitionManagementService partitionManagementService =
        new PartitionManagementService(jdbi, 10, 12);
    DenormalizedLineageService denormalizedLineageService =
        new DenormalizedLineageService(jdbi, partitionManagementService);

    denormalizedLineageService.populateAllDenormalizedEntities();
    denormalizedLineageService.populateLineageForRun(runId);
  }

  private String buildCompleteEvent(
      UUID runId, String jobName, String inputDataset, String outputDataset) {
    return String.format(
        """
        {
          \"eventType\": \"COMPLETE\",
          \"eventTime\": \"%s\",
          \"run\": {
            \"runId\": \"%s\"
          },
          \"job\": {
            \"namespace\": \"%s\",
            \"name\": \"%s\"
          },
          \"inputs\": [
            {
              \"namespace\": \"%s\",
              \"name\": \"%s\"
            }
          ],
          \"outputs\": [
            {
              \"namespace\": \"%s\",
              \"name\": \"%s\"
            }
          ],
          \"producer\": \"https://github.com/OpenLineage/OpenLineage/blob/v1-0-0/client\",
          \"schemaURL\": \"https://openlineage.io/spec/1-0-1/OpenLineage.json#/definitions/RunEvent\"
        }
        """,
        Instant.now(),
        runId,
        NAMESPACE_NAME,
        jobName,
        NAMESPACE_NAME,
        inputDataset,
        NAMESPACE_NAME,
        outputDataset);
  }

  private HttpResponse<String> fetchLineageV2WithParams(
      String nodeId, int depth, boolean aggregateToParentRun, String includeFacet)
      throws Exception {
    String encodedNodeId = URLEncoder.encode(nodeId, StandardCharsets.UTF_8);
    StringBuilder query =
        new StringBuilder(
            "/api/v2/lineage?nodeId="
                + encodedNodeId
                + "&depth="
                + depth
                + "&aggregateToParentRun="
                + aggregateToParentRun);
    if (includeFacet != null && !includeFacet.isBlank()) {
      query
          .append("&includeFacets=")
          .append(URLEncoder.encode(includeFacet, StandardCharsets.UTF_8));
    }

    URI uri = new URI(baseUrl.toString() + query);
    HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
    return http2.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> fetchLineageV1WithParams(
      String nodeId, int depth, boolean aggregateToParentRun, String includeFacet)
      throws Exception {
    String encodedNodeId = URLEncoder.encode(nodeId, StandardCharsets.UTF_8);
    StringBuilder query =
        new StringBuilder(
            "/api/v1/lineage?nodeId="
                + encodedNodeId
                + "&depth="
                + depth
                + "&aggregateToParentRun="
                + aggregateToParentRun);
    if (includeFacet != null && !includeFacet.isBlank()) {
      query
          .append("&includeFacets=")
          .append(URLEncoder.encode(includeFacet, StandardCharsets.UTF_8));
    }

    URI uri = new URI(baseUrl.toString() + query);
    HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
    return http2.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void assertLineageParity(
      String nodeId, int depth, boolean aggregateToParentRun, String includeFacet)
      throws Exception {
    HttpResponse<String> v1Response =
        fetchLineageV1WithParams(nodeId, depth, aggregateToParentRun, includeFacet);
    HttpResponse<String> v2Response =
        fetchLineageV2WithParams(nodeId, depth, aggregateToParentRun, includeFacet);

    assertThat(v1Response.statusCode()).isEqualTo(200);
    assertThat(v2Response.statusCode()).isEqualTo(200);
    assertThat(normalizeJson(mapper.readTree(v2Response.body())))
        .isEqualTo(normalizeJson(mapper.readTree(v1Response.body())));
  }

  private JsonNode normalizeJson(JsonNode node) {
    if (node == null || node.isNull() || node.isValueNode()) {
      return node;
    }

    if (node.isArray()) {
      ArrayNode normalizedArray = mapper.createArrayNode();
      List<JsonNode> normalizedChildren = new ArrayList<>();
      node.forEach(child -> normalizedChildren.add(normalizeJson(child)));
      normalizedChildren.sort(Comparator.comparing(JsonNode::toString));
      normalizedChildren.forEach(normalizedArray::add);
      return normalizedArray;
    }

    ObjectNode normalizedObject = mapper.createObjectNode();
    List<String> fieldNames = new ArrayList<>();
    node.fieldNames().forEachRemaining(fieldNames::add);
    fieldNames.sort(String::compareTo);
    fieldNames.forEach(
        fieldName -> normalizedObject.set(fieldName, normalizeJson(node.get(fieldName))));
    return normalizedObject;
  }

  private Set<String> graphNodeIds(String body) throws Exception {
    JsonNode graph = mapper.readTree(body).path("graph");
    return java.util.stream.StreamSupport.stream(graph.spliterator(), false)
        .map(node -> node.path("id").asText())
        .collect(Collectors.toSet());
  }
}

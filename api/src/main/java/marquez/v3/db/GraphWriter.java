/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import marquez.service.models.LineageEvent;
import org.jdbi.v3.core.Handle;

/**
 * Writes OpenLineage events into the Apache AGE property graph.
 *
 * <h2>Graph schema</h2>
 *
 * <p><strong>Node labels:</strong> {@code Source}, {@code Namespace}, {@code Job}, {@code
 * JobVersion}, {@code Run}, {@code Dataset}, {@code DatasetVersion}, {@code DatasetField}
 *
 * <p><strong>Edge types (directed):</strong>
 *
 * <pre>
 * (Source)       -[:HAS_NAMESPACE]->   (Namespace)
 * (Namespace)    -[:CONTAINS]->        (Job)
 * (Namespace)    -[:CONTAINS]->        (Dataset)
 * (Job)          -[:HAS_JOB_VERSION]-> (JobVersion)
 * (JobVersion)   -[:HAS_RUN]->         (Run)
 * (Run)          -[:RUN_OF]->          (Job)           -- direct shortcut
 * (Run)          -[:HAS_CHILD_RUN]->   (Run)           -- parent -> child
 * (Dataset)      -[:HAS_DATASET_VERSION]-> (DatasetVersion)
 * (DatasetVersion)-[:VERSION_OF]->     (Dataset)       -- reverse pointer
 * (Run)          -[:READS]->           (DatasetVersion)
 * (Run)          -[:WRITES]->          (DatasetVersion)
 * (Dataset)      -[:INPUT_TO]->        (Job)           -- job-level shortcut
 * (Job)          -[:PRODUCES]->        (Dataset)       -- job-level shortcut
 * (DatasetVersion)-[:HAS_FIELD]->      (DatasetField)
 * </pre>
 *
 * <h2>Design notes</h2>
 *
 * <ul>
 *   <li>All upserts use Cypher {@code MERGE} so they are idempotent.
 *   <li>{@code RunState} is stored as a property on the {@code Run} node (not a separate label) to
 *       avoid creating O(events) redundant nodes.
 *   <li>The parent-to-child direction on {@code HAS_CHILD_RUN} makes aggregation queries
 *       forward-traversals: {@code MATCH (p:Run)-[:HAS_CHILD_RUN*]->(c:Run)}.
 *   <li>{@code PRODUCES} replaces the former {@code OUTPUT_FROM} label whose direction was
 *       semantically ambiguous.
 * </ul>
 *
 * <p><strong>Pre-condition:</strong> {@link GraphDao#initAgeSession(Connection)} must be called
 * <em>once</em> on the JDBC connection backing the {@code handle} before any write call.
 */
@Slf4j
@RequiredArgsConstructor
public class GraphWriter {

  static final String GRAPH_NAME = "marquez_graph";

  private final GraphDao graphDao;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Writes a single {@link LineageEvent} into the graph within the supplied handle's transaction.
   *
   * @param handle an open JDBI handle whose connection has an active AGE session
   * @param event the OpenLineage event to persist
   * @throws RuntimeException wrapping any {@link SQLException} so callers can handle via JDBI
   */
  public void writeEvent(Handle handle, LineageEvent event) {
    if (!GraphDao.isAgeAvailable()) {
      return;
    }
    try {
      doWrite(handle, event);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to write lineage event to graph", e);
    }
  }

  // ---------------------------------------------------------------------------
  // Internal write logic
  // ---------------------------------------------------------------------------

  private void doWrite(Handle handle, LineageEvent event) throws SQLException {
    writeNamespaceAndSource(handle, event.getJob().getNamespace());
    writeJob(handle, event);
    writeJobVersion(handle, event);
    writeRun(handle, event);
    writeParentRun(handle, event);
    writeInputDatasets(handle, event);
    writeOutputDatasets(handle, event);
  }

  private void writeNamespaceAndSource(Handle handle, String namespace) throws SQLException {
    writeNamespaceAndSource(handle, namespace, "default", "unknown", null);
  }

  private void writeNamespaceAndSource(
      Handle handle, String namespace, String sourceName, String sourceType, String connectionUrl)
      throws SQLException {
    if (namespace == null) return;

    Map<String, Object> srcProps = new HashMap<>();
    srcProps.put("name", sourceName);
    srcProps.put("type", sourceType);
    if (connectionUrl != null && !connectionUrl.isEmpty()) {
      srcProps.put("connectionUrl", connectionUrl);
    }
    graphDao.upsertNode(handle, GRAPH_NAME, "Source", "name", srcProps);

    Map<String, Object> nsProps = new HashMap<>();
    nsProps.put("name", namespace);
    graphDao.upsertNode(handle, GRAPH_NAME, "Namespace", "name", nsProps);

    graphDao.upsertEdge(
        handle,
        GRAPH_NAME,
        "HAS_NAMESPACE",
        "Source",
        "name",
        sourceName,
        "Namespace",
        "name",
        namespace);
  }

  private void writeJob(Handle handle, LineageEvent event) throws SQLException {
    String ns = event.getJob().getNamespace();
    String name = event.getJob().getName();
    String fqn = fqn(ns, name);

    Map<String, Object> jobProps = new HashMap<>();
    jobProps.put("fqn", fqn);
    jobProps.put("name", name);
    jobProps.put("namespace", ns);
    jobProps.put("simpleName", simpleName(name));
    if (event.getJob().getFacets() != null) {
      jobProps.put("facets", safeJson(event.getJob().getFacets()));
    }
    graphDao.upsertNode(handle, GRAPH_NAME, "Job", "fqn", jobProps);

    graphDao.upsertEdge(handle, GRAPH_NAME, "CONTAINS", "Namespace", "name", ns, "Job", "fqn", fqn);
  }

  private void writeJobVersion(Handle handle, LineageEvent event) throws SQLException {
    String jobFqn = fqn(event.getJob().getNamespace(), event.getJob().getName());
    String jvUuid =
        generateDeterministicUuid(
            jobFqn
                + safeJson(event.getJob().getFacets())
                + safeJson(event.getInputs())
                + safeJson(event.getOutputs()));

    Map<String, Object> jvProps = new HashMap<>();
    jvProps.put("uuid", jvUuid);
    jvProps.put("version", jvUuid);
    jvProps.put("jobFqn", jobFqn);
    jvProps.put("jobContext", safeJson(event.getJob().getFacets()));
    graphDao.upsertNode(handle, GRAPH_NAME, "JobVersion", "uuid", jvProps);

    graphDao.upsertEdge(
        handle, GRAPH_NAME, "HAS_JOB_VERSION", "Job", "fqn", jobFqn, "JobVersion", "uuid", jvUuid);
  }

  private void writeRun(Handle handle, LineageEvent event) throws SQLException {
    String jobFqn = fqn(event.getJob().getNamespace(), event.getJob().getName());
    String jvUuid =
        generateDeterministicUuid(
            jobFqn
                + safeJson(event.getJob().getFacets())
                + safeJson(event.getInputs())
                + safeJson(event.getOutputs()));

    String runId = event.getRun().getRunId();
    String eventType = event.getEventType() != null ? event.getEventType() : "START";
    String eventTimeStr =
        event.getEventTime() != null
            ? event.getEventTime().toInstant().toString()
            : Instant.now().toString();

    Map<String, Object> runProps = new HashMap<>();
    runProps.put("runId", runId);
    runProps.put("fqn", jobFqn);
    runProps.put("jobName", event.getJob().getName());
    runProps.put("jobNamespace", event.getJob().getNamespace());
    runProps.put("state", eventType);
    runProps.put("createdAt", eventTimeStr);
    runProps.put("updatedAt", eventTimeStr);
    // startedAt / endedAt are set on START and terminal events respectively
    if ("START".equalsIgnoreCase(eventType) || "RUNNING".equalsIgnoreCase(eventType)) {
      runProps.put("startedAt", eventTimeStr);
    }
    if ("COMPLETE".equalsIgnoreCase(eventType)
        || "FAIL".equalsIgnoreCase(eventType)
        || "ABORT".equalsIgnoreCase(eventType)) {
      runProps.put("endedAt", eventTimeStr);
    }
    runProps.put("durationMs", 0);
    if (event.getRun().getFacets() != null) {
      runProps.put("facets", safeJson(event.getRun().getFacets()));
    }
    graphDao.upsertNode(handle, GRAPH_NAME, "Run", "runId", runProps);

    // JobVersion -> Run
    graphDao.upsertEdge(
        handle, GRAPH_NAME, "HAS_RUN", "JobVersion", "uuid", jvUuid, "Run", "runId", runId);

    // Run -> Job direct shortcut (avoids 2-hop lookup via JobVersion)
    graphDao.upsertEdge(handle, GRAPH_NAME, "RUN_OF", "Run", "runId", runId, "Job", "fqn", jobFqn);
  }

  private void writeParentRun(Handle handle, LineageEvent event) throws SQLException {
    if (event.getRun().getFacets() == null || event.getRun().getFacets().getParent() == null) {
      return;
    }

    String parentRunId = event.getRun().getFacets().getParent().getRun().getRunId();
    if (parentRunId == null || parentRunId.isEmpty()) return;

    String childRunId = event.getRun().getRunId();

    // Ensure parent run node exists (minimal properties; will be enriched when its own event
    // arrives)
    Map<String, Object> parentProps = new HashMap<>();
    parentProps.put("runId", parentRunId);
    parentProps.put("state", "RUNNING");
    graphDao.upsertNode(handle, GRAPH_NAME, "Run", "runId", parentProps);

    // Direction: parent -[:HAS_CHILD_RUN]-> child
    // This makes MATCH (parent)-[:HAS_CHILD_RUN*]->(descendant) a forward traversal
    graphDao.upsertEdge(
        handle,
        GRAPH_NAME,
        "HAS_CHILD_RUN",
        "Run",
        "runId",
        parentRunId,
        "Run",
        "runId",
        childRunId);
  }

  private void writeInputDatasets(Handle handle, LineageEvent event) throws SQLException {
    if (event.getInputs() == null) return;

    String jobFqn = fqn(event.getJob().getNamespace(), event.getJob().getName());
    String runId = event.getRun().getRunId();

    for (LineageEvent.Dataset ds : event.getInputs()) {
      String dsFqn = fqn(ds.getNamespace(), ds.getName());
      String dvUuid = generateDeterministicUuid(dsFqn + safeJson(ds.getFacets()));

      writeDatasetNode(handle, ds, dsFqn);
      writeDatasetVersionNode(handle, ds, dsFqn, dvUuid);

      // Run reads this dataset version
      graphDao.upsertEdge(
          handle, GRAPH_NAME, "READS", "Run", "runId", runId, "DatasetVersion", "uuid", dvUuid);

      // Job-level shortcut: Dataset -> Job
      graphDao.upsertEdge(
          handle, GRAPH_NAME, "INPUT_TO", "Dataset", "fqn", dsFqn, "Job", "fqn", jobFqn);

      writeDatasetFields(handle, ds, dvUuid);
    }
  }

  private void writeOutputDatasets(Handle handle, LineageEvent event) throws SQLException {
    if (event.getOutputs() == null) return;

    String jobFqn = fqn(event.getJob().getNamespace(), event.getJob().getName());
    String runId = event.getRun().getRunId();

    // Build a lookup map: inputDsFqn -> inputDvUuid so that column lineage can resolve
    // input DatasetField ids (which are keyed by dvUuid:fieldName, matching V1/V2 which is also
    // version-scoped).
    Map<String, String> inputDvUuidByFqn = new HashMap<>();
    if (event.getInputs() != null) {
      for (LineageEvent.Dataset inputDs : event.getInputs()) {
        String inputFqn = fqn(inputDs.getNamespace(), inputDs.getName());
        inputDvUuidByFqn.put(
            inputFqn, generateDeterministicUuid(inputFqn + safeJson(inputDs.getFacets())));
      }
    }

    for (LineageEvent.Dataset ds : event.getOutputs()) {
      String dsFqn = fqn(ds.getNamespace(), ds.getName());
      String dvUuid = generateDeterministicUuid(dsFqn + safeJson(ds.getFacets()));

      writeDatasetNode(handle, ds, dsFqn);
      writeDatasetVersionNode(handle, ds, dsFqn, dvUuid);

      // Run writes this dataset version
      graphDao.upsertEdge(
          handle, GRAPH_NAME, "WRITES", "Run", "runId", runId, "DatasetVersion", "uuid", dvUuid);

      // Job-level shortcut: Job -> Dataset
      graphDao.upsertEdge(
          handle, GRAPH_NAME, "PRODUCES", "Job", "fqn", jobFqn, "Dataset", "fqn", dsFqn);

      writeDatasetFields(handle, ds, dvUuid);
      writeColumnLineage(handle, ds, dvUuid, inputDvUuidByFqn);
    }
  }

  /**
   * Writes {@code DERIVED_FROM} edges between output {@link DatasetField} nodes and their source
   * input {@link DatasetField} nodes, matching the V1/V2 {@code column_lineage} table which is also
   * version-scoped (keyed by output_dataset_version_uuid + input_dataset_version_uuid).
   *
   * <p>The OpenLineage {@code columnLineage} facet lives on the <em>output</em> dataset and lists,
   * for each output field, the input fields that contributed to it:
   *
   * <pre>
   * outputs[].facets.columnLineage.fields = {
   *   "output_col": {
   *     inputFields: [{ namespace, name, field }],
   *     transformationType: "IDENTITY",
   *     transformationDescription: "..."
   *   }
   * }
   * </pre>
   */
  private void writeColumnLineage(
      Handle handle,
      LineageEvent.Dataset outputDs,
      String outputDvUuid,
      Map<String, String> inputDvUuidByFqn)
      throws SQLException {
    if (outputDs.getFacets() == null || outputDs.getFacets().getColumnLineage() == null) return;
    LineageEvent.ColumnLineageDatasetFacet clFacet = outputDs.getFacets().getColumnLineage();
    if (clFacet.getFields() == null || clFacet.getFields().getAdditionalFacets() == null) return;

    for (Map.Entry<String, LineageEvent.ColumnLineageOutputColumn> entry :
        clFacet.getFields().getAdditionalFacets().entrySet()) {
      String outputFieldName = entry.getKey();
      LineageEvent.ColumnLineageOutputColumn outputCol = entry.getValue();
      if (outputCol == null || outputCol.getInputFields() == null) continue;

      String outputFieldId = outputDvUuid + ":" + outputFieldName;

      for (LineageEvent.ColumnLineageInputField inputRef : outputCol.getInputFields()) {
        String inputFqn = fqn(inputRef.getNamespace(), inputRef.getName());
        String inputDvUuid = inputDvUuidByFqn.get(inputFqn);
        if (inputDvUuid == null) continue; // input dataset not in this event; skip

        String inputFieldId = inputDvUuid + ":" + inputRef.getField();

        // Edge properties: transformationType and transformationDescription for provenance
        Map<String, Object> edgeProps = new HashMap<>();
        if (outputCol.getTransformationType() != null) {
          edgeProps.put("transformationType", outputCol.getTransformationType());
        }
        if (outputCol.getTransformationDescription() != null) {
          edgeProps.put("transformationDescription", outputCol.getTransformationDescription());
        }

        graphDao.upsertEdgeWithProps(
            handle,
            GRAPH_NAME,
            "DERIVED_FROM",
            "DatasetField",
            "id",
            outputFieldId,
            "DatasetField",
            "id",
            inputFieldId,
            edgeProps);
      }
    }
  }

  private void writeDatasetNode(Handle handle, LineageEvent.Dataset ds, String dsFqn)
      throws SQLException {
    // Extract real source name + connectionUrl from the OpenLineage dataSource facet when present,
    // matching V1/V2 parity (OpenLineageDao uses ds.getFacets().getDataSource() the same way).
    String sourceName = "default";
    String sourceType = "unknown";
    String connectionUrl = null;
    if (ds.getFacets() != null && ds.getFacets().getDataSource() != null) {
      LineageEvent.DatasourceDatasetFacet dsf = ds.getFacets().getDataSource();
      if (dsf.getName() != null && !dsf.getName().isEmpty()) {
        sourceName = dsf.getName();
      }
      if (dsf.getUri() != null && !dsf.getUri().isEmpty()) {
        connectionUrl = dsf.getUri();
        // Derive a rough type from the URI scheme (e.g. "postgresql", "s3", "bigquery")
        int colonIdx = connectionUrl.indexOf(':');
        if (colonIdx > 0) {
          sourceType = connectionUrl.substring(0, colonIdx).toUpperCase();
        }
      }
    }

    // Ensure the source node and HAS_NAMESPACE edge exist for this dataset's namespace
    writeNamespaceAndSource(handle, ds.getNamespace(), sourceName, sourceType, connectionUrl);

    Map<String, Object> dsProps = new HashMap<>();
    dsProps.put("fqn", dsFqn);
    dsProps.put("name", ds.getName());
    dsProps.put("namespace", ds.getNamespace());
    dsProps.put("physicalName", ds.getName());
    dsProps.put("sourceName", sourceName);
    if (connectionUrl != null) {
      dsProps.put("connectionUrl", connectionUrl);
    }
    dsProps.put("type", "DB_TABLE");
    if (ds.getFacets() != null) {
      dsProps.put("facets", safeJson(ds.getFacets()));
    }
    graphDao.upsertNode(handle, GRAPH_NAME, "Dataset", "fqn", dsProps);

    graphDao.upsertEdge(
        handle,
        GRAPH_NAME,
        "CONTAINS",
        "Namespace",
        "name",
        ds.getNamespace(),
        "Dataset",
        "fqn",
        dsFqn);
  }

  private void writeDatasetVersionNode(
      Handle handle, LineageEvent.Dataset ds, String dsFqn, String dvUuid) throws SQLException {
    String now = Instant.now().toString();

    Map<String, Object> dvProps = new HashMap<>();
    dvProps.put("uuid", dvUuid);
    dvProps.put("datasetFqn", dsFqn);
    dvProps.put("name", ds.getName());
    dvProps.put("namespace", ds.getNamespace());
    dvProps.put("createdAt", now);
    if (ds.getFacets() != null) {
      dvProps.put("facets", safeJson(ds.getFacets()));
      // Extract schema fields count for quick access
      if (ds.getFacets().getSchema() != null && ds.getFacets().getSchema().getFields() != null) {
        dvProps.put("fieldCount", ds.getFacets().getSchema().getFields().size());
      }
    }
    graphDao.upsertNode(handle, GRAPH_NAME, "DatasetVersion", "uuid", dvProps);

    // Dataset -> DatasetVersion
    graphDao.upsertEdge(
        handle,
        GRAPH_NAME,
        "HAS_DATASET_VERSION",
        "Dataset",
        "fqn",
        dsFqn,
        "DatasetVersion",
        "uuid",
        dvUuid);

    // DatasetVersion -> Dataset (reverse pointer for efficient back-lookup)
    graphDao.upsertEdge(
        handle,
        GRAPH_NAME,
        "VERSION_OF",
        "DatasetVersion",
        "uuid",
        dvUuid,
        "Dataset",
        "fqn",
        dsFqn);
  }

  private void writeDatasetFields(Handle handle, LineageEvent.Dataset ds, String dvUuid)
      throws SQLException {
    if (ds.getFacets() == null
        || ds.getFacets().getSchema() == null
        || ds.getFacets().getSchema().getFields() == null) {
      return;
    }
    for (LineageEvent.SchemaField field : ds.getFacets().getSchema().getFields()) {
      String fieldId = dvUuid + ":" + field.getName();
      Map<String, Object> fieldProps = new HashMap<>();
      fieldProps.put("id", fieldId);
      fieldProps.put("name", field.getName());
      fieldProps.put("type", field.getType() != null ? field.getType() : "UNKNOWN");
      if (field.getDescription() != null) {
        fieldProps.put("description", field.getDescription());
      }
      graphDao.upsertNode(handle, GRAPH_NAME, "DatasetField", "id", fieldProps);

      graphDao.upsertEdge(
          handle,
          GRAPH_NAME,
          "HAS_FIELD",
          "DatasetVersion",
          "uuid",
          dvUuid,
          "DatasetField",
          "id",
          fieldId);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Returns {@code namespace:name} as the fully-qualified identifier. */
  static String fqn(String namespace, String name) {
    return namespace + ":" + name;
  }

  /**
   * Returns the last path segment of a job name (everything after the final {@code /}). For names
   * without a {@code /} separator the full name is returned.
   */
  static String simpleName(String name) {
    if (name == null) return "";
    int idx = name.lastIndexOf('/');
    return idx >= 0 ? name.substring(idx + 1) : name;
  }

  /** Serialises an object to JSON, returning {@code "{}"} on failure. */
  static String safeJson(Object obj) {
    if (obj == null) return "{}";
    try {
      return MAPPER.writeValueAsString(obj);
    } catch (Exception e) {
      return "{}";
    }
  }

  /**
   * Generates a stable UUID from a string using UUID v3 (name-based MD5). This ensures the same
   * logical entity always gets the same graph node UUID across re-ingestions.
   */
  static String generateDeterministicUuid(String input) {
    return java.util
        .UUID
        .nameUUIDFromBytes(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toString();
  }
}

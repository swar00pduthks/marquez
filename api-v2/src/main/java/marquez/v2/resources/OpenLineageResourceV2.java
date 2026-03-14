/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v2.resources;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jdbi.v3.core.Jdbi;
import marquez.v2.db.GraphDao;
import marquez.service.models.LineageEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Path("/api/v2/lineage")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OpenLineageResourceV2 {

    private final Jdbi jdbi;
    private final GraphDao graphDao;
    private static final String GRAPH_NAME = "marquez_graph";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public OpenLineageResourceV2(Jdbi jdbi, GraphDao graphDao) {
        this.jdbi = jdbi;
        this.graphDao = graphDao;
        this.graphDao.initGraph(jdbi, GRAPH_NAME);
    }

    private String generateDeterministicUuid(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return UUID.nameUUIDFromBytes(hash).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not found", e);
        }
    }

    private String safeJson(Object obj) {
        if (obj == null) return "{}";
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    @POST
    public Response createLineage(LineageEvent event) {
        if (event == null || event.getJob() == null || event.getRun() == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Missing Job or Run in Lineage Event").build();
        }

        // Execute the entire graph ingestion in a single transaction for atomicity and performance
        jdbi.useTransaction(handle -> {

            // 1. Source and Namespace
            String sourceName = "default";
            if (event.getJob().getNamespace() != null) {
                Map<String, Object> srcProps = new HashMap<>();
                srcProps.put("name", sourceName);
                srcProps.put("type", "unknown");
                graphDao.upsertNode(handle, GRAPH_NAME, "Source", "name", srcProps);

                Map<String, Object> nsProps = new HashMap<>();
                nsProps.put("name", event.getJob().getNamespace());
                if (event.getRun().getFacets() != null) {
                    nsProps.put("facets", event.getRun().getFacets());
                }
                graphDao.upsertNode(handle, GRAPH_NAME, "Namespace", "name", nsProps);
                graphDao.upsertEdge(handle, GRAPH_NAME, "HAS_NAMESPACE", "Source", "name", sourceName, "Namespace", "name", event.getJob().getNamespace());
            }

            // 2. Job and JobVersion
            String jobFqn = event.getJob().getNamespace() + ":" + event.getJob().getName();
            Map<String, Object> jobProps = new HashMap<>();
            jobProps.put("name", event.getJob().getName());
            jobProps.put("namespace", event.getJob().getNamespace());
            jobProps.put("fqn", jobFqn);
            if (event.getJob().getFacets() != null) {
                jobProps.put("facets", event.getJob().getFacets());
            }
            graphDao.upsertNode(handle, GRAPH_NAME, "Job", "fqn", jobProps);
            graphDao.upsertEdge(handle, GRAPH_NAME, "HAS_JOB", "Namespace", "name", event.getJob().getNamespace(), "Job", "fqn", jobFqn);

            String jobContextJson = safeJson(event.getJob().getFacets());
            String jobInputs = safeJson(event.getInputs());
            String jobOutputs = safeJson(event.getOutputs());
            String jobVersionSignature = jobFqn + jobContextJson + jobInputs + jobOutputs;
            String jobVersionUuid = generateDeterministicUuid(jobVersionSignature);

            Map<String, Object> jvProps = new HashMap<>();
            jvProps.put("uuid", jobVersionUuid);
            jvProps.put("version", jobVersionUuid);
            jvProps.put("jobContext", jobContextJson);
            graphDao.upsertNode(handle, GRAPH_NAME, "JobVersion", "uuid", jvProps);
            graphDao.upsertEdge(handle, GRAPH_NAME, "HAS_VERSION", "Job", "fqn", jobFqn, "JobVersion", "uuid", jobVersionUuid);

            // 3. Run and RunState
            String runId = event.getRun().getRunId();
            String eventType = event.getEventType() != null ? event.getEventType() : "START";

            Map<String, Object> runProps = new HashMap<>();
            runProps.put("uuid", runId);
            runProps.put("state", eventType);
            if (event.getRun().getFacets() != null) {
                runProps.put("runArgs", event.getRun().getFacets());
            }
            graphDao.upsertNode(handle, GRAPH_NAME, "Run", "uuid", runProps);
            graphDao.upsertEdge(handle, GRAPH_NAME, "HAS_RUN", "JobVersion", "uuid", jobVersionUuid, "Run", "uuid", runId);

            String runStateUuid = generateDeterministicUuid(runId + eventType);
            Map<String, Object> rsProps = new HashMap<>();
            rsProps.put("uuid", runStateUuid);
            rsProps.put("state", eventType);
            graphDao.upsertNode(handle, GRAPH_NAME, "RunState", "uuid", rsProps);
            graphDao.upsertEdge(handle, GRAPH_NAME, "HAS_STATE", "Run", "uuid", runId, "RunState", "uuid", runStateUuid);

            // 4. Inputs (Datasets, DatasetVersions, and Fields)
            if (event.getInputs() != null) {
                for (LineageEvent.Dataset ds : event.getInputs()) {
                    String dsFqn = ds.getNamespace() + ":" + ds.getName();
                    Map<String, Object> dsProps = new HashMap<>();
                    dsProps.put("name", ds.getName());
                    dsProps.put("namespace", ds.getNamespace());
                    dsProps.put("fqn", dsFqn);
                    if (ds.getFacets() != null) dsProps.put("facets", ds.getFacets());
                    graphDao.upsertNode(handle, GRAPH_NAME, "Dataset", "fqn", dsProps);
                    graphDao.upsertEdge(handle, GRAPH_NAME, "HAS_DATASET", "Namespace", "name", ds.getNamespace(), "Dataset", "fqn", dsFqn);

                    String dsSchemaJson = safeJson(ds.getFacets());
                    String dvUuid = generateDeterministicUuid(dsFqn + dsSchemaJson);

                    Map<String, Object> dvProps = new HashMap<>();
                    dvProps.put("uuid", dvUuid);
                    dvProps.put("datasetFqn", dsFqn);
                    graphDao.upsertNode(handle, GRAPH_NAME, "DatasetVersion", "uuid", dvProps);
                    graphDao.upsertEdge(handle, GRAPH_NAME, "HAS_VERSION", "Dataset", "fqn", dsFqn, "DatasetVersion", "uuid", dvUuid);

                    graphDao.upsertEdge(handle, GRAPH_NAME, "CONSUMES_VERSION", "Run", "uuid", runId, "DatasetVersion", "uuid", dvUuid);
                }
            }

            // 5. Outputs (Datasets, DatasetVersions, and Fields)
            if (event.getOutputs() != null) {
                for (LineageEvent.Dataset ds : event.getOutputs()) {
                    String dsFqn = ds.getNamespace() + ":" + ds.getName();
                    Map<String, Object> dsProps = new HashMap<>();
                    dsProps.put("name", ds.getName());
                    dsProps.put("namespace", ds.getNamespace());
                    dsProps.put("fqn", dsFqn);
                    if (ds.getFacets() != null) dsProps.put("facets", ds.getFacets());
                    graphDao.upsertNode(handle, GRAPH_NAME, "Dataset", "fqn", dsProps);
                    graphDao.upsertEdge(handle, GRAPH_NAME, "HAS_DATASET", "Namespace", "name", ds.getNamespace(), "Dataset", "fqn", dsFqn);

                    String dsSchemaJson = safeJson(ds.getFacets());
                    String dvUuid = generateDeterministicUuid(dsFqn + dsSchemaJson);

                    Map<String, Object> dvProps = new HashMap<>();
                    dvProps.put("uuid", dvUuid);
                    dvProps.put("datasetFqn", dsFqn);
                    graphDao.upsertNode(handle, GRAPH_NAME, "DatasetVersion", "uuid", dvProps);
                    graphDao.upsertEdge(handle, GRAPH_NAME, "HAS_VERSION", "Dataset", "fqn", dsFqn, "DatasetVersion", "uuid", dvUuid);

                    graphDao.upsertEdge(handle, GRAPH_NAME, "PRODUCES_VERSION", "Run", "uuid", runId, "DatasetVersion", "uuid", dvUuid);

                    // Column Lineage
                    if (ds.getFacets() != null && ds.getFacets().getSchema() != null && ds.getFacets().getSchema().getFields() != null) {
                        for (LineageEvent.SchemaField field : ds.getFacets().getSchema().getFields()) {
                            String fieldId = dvUuid + ":" + field.getName();
                            Map<String, Object> fieldProps = new HashMap<>();
                            fieldProps.put("id", fieldId);
                            fieldProps.put("name", field.getName());
                            fieldProps.put("type", field.getType());
                            graphDao.upsertNode(handle, GRAPH_NAME, "DatasetField", "id", fieldProps);
                            graphDao.upsertEdge(handle, GRAPH_NAME, "HAS_FIELD", "DatasetVersion", "uuid", dvUuid, "DatasetField", "id", fieldId);

                            // Link Run applies transformation to field
                            graphDao.upsertEdge(handle, GRAPH_NAME, "APPLIES_TRANSFORMATION", "Run", "uuid", runId, "DatasetField", "id", fieldId);
                        }
                    }
                }
            }
        });

        return Response.status(Response.Status.CREATED).build();
    }
}

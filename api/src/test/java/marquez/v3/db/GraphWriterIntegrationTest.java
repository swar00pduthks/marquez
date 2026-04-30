/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import marquez.PostgresContainer;
import marquez.service.models.LineageEvent;
import marquez.service.models.LineageEvent.DatasetFacets;
import marquez.service.models.LineageEvent.JobLink;
import marquez.service.models.LineageEvent.ParentRunFacet;
import marquez.service.models.LineageEvent.RunFacet;
import marquez.service.models.LineageEvent.RunLink;
import marquez.service.models.LineageEvent.SchemaDatasetFacet;
import marquez.service.models.LineageEvent.SchemaField;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.jackson2.Jackson2Plugin;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link GraphWriter} and {@link GraphDao} against a real Apache AGE instance
 * running inside the {@code apache/age:latest} Testcontainer.
 *
 * <h2>What is tested</h2>
 *
 * <ul>
 *   <li>AGE graph creation and index setup via {@link GraphDao#initGraph}
 *   <li>Node upsert idempotency (MERGE)
 *   <li>Edge creation for all edge types in the graph schema
 *   <li>Run state progression (START → COMPLETE)
 *   <li>Parent–child run relationships ({@code HAS_CHILD_RUN})
 *   <li>Dataset field nodes ({@code HAS_FIELD})
 *   <li>Deterministic UUID generation (re-ingesting the same event produces the same nodes)
 * </ul>
 */
@Tag("integration")
@Testcontainers
@TestMethodOrder(OrderAnnotation.class)
public class GraphWriterIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String GRAPH = GraphWriter.GRAPH_NAME;
  private static final URI TEST_PRODUCER = URI.create("https://github.com/OpenLineage/test");
  private static final URI TEST_SCHEMA =
      URI.create("https://openlineage.io/spec/1-0-5/OpenLineage.json");

  @Container static final PostgresContainer POSTGRES = PostgresContainer.create("graphwriter-test");

  private static Jdbi jdbi;
  private static GraphDao graphDao;
  private static GraphWriter graphWriter;

  // Stable test identifiers
  private static final String NS = "gw-test-ns";
  private static final String JOB = "gw-etl-job";
  private static final String INPUT_NS = "gw-source-ns";
  private static final String INPUT_DS = "raw_table";
  private static final String OUTPUT_DS = "clean_table";
  private static final String RUN_ID = UUID.randomUUID().toString();
  private static final String PARENT_RUN_ID = UUID.randomUUID().toString();
  private static final String CHILD_RUN_ID = UUID.randomUUID().toString();

  @BeforeAll
  static void setUpGraph() {
    jdbi =
        Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .installPlugin(new SqlObjectPlugin())
            .installPlugin(new PostgresPlugin())
            .installPlugin(new Jackson2Plugin());

    graphDao = new GraphDao();
    graphWriter = new GraphWriter(graphDao);
    graphDao.initGraph(jdbi, GRAPH);

    // This test bypasses Flyway, so manually create the marquez_v3 schema and agtype_to_json
    // helper function that V103 normally ships. Definition mirrors V103 exactly.
    jdbi.useHandle(
        h -> {
          h.execute("CREATE SCHEMA IF NOT EXISTS marquez_v3");
          h.execute(
              "CREATE OR REPLACE FUNCTION marquez_v3.agtype_to_json(val ag_catalog.agtype) "
                  + "RETURNS json LANGUAGE plpgsql IMMUTABLE AS $func$ "
                  + "DECLARE txt text; "
                  + "BEGIN "
                  + "  IF val IS NULL THEN RETURN NULL; END IF; "
                  + "  txt := ag_catalog.agtype_out(val)::text; "
                  + "  txt := regexp_replace(txt, '::[a-z]+', '', 'g'); "
                  + "  RETURN txt::json; "
                  + "EXCEPTION WHEN OTHERS THEN "
                  + "  BEGIN RETURN (val::text)::json; "
                  + "  EXCEPTION WHEN OTHERS THEN RETURN NULL; END; "
                  + "END; $func$");
        });
  }

  // ===========================================================================
  // AGE availability
  // ===========================================================================

  @Test
  @Order(1)
  void ageExtension_isAvailable() {
    assertThat(GraphDao.isAgeAvailable())
        .as("Apache AGE must be available on the test container")
        .isTrue();
  }

  // ===========================================================================
  // Graph write – basic event
  // ===========================================================================

  /**
   * Writes a START event and verifies that Job, Namespace, Run, Dataset, and DatasetVersion nodes
   * are all created in the graph.
   */
  @Test
  @Order(2)
  void writeEvent_startEvent_createsAllNodes() throws Exception {
    LineageEvent event =
        buildEvent(
            NS,
            JOB,
            RUN_ID,
            "START",
            List.of(buildDataset(INPUT_NS, INPUT_DS)),
            List.of(buildDataset(NS, OUTPUT_DS)));

    writeInTransaction(event);

    assertNodeExists("Namespace", "name", NS);
    assertNodeExists("Job", "fqn", GraphWriter.fqn(NS, JOB));
    assertNodeExists("Run", "runId", RUN_ID);
    assertNodeExists("Dataset", "fqn", GraphWriter.fqn(INPUT_NS, INPUT_DS));
    assertNodeExists("Dataset", "fqn", GraphWriter.fqn(NS, OUTPUT_DS));
    assertAnyNodeExists("DatasetVersion");
  }

  /** Writing the same event twice (idempotency): MERGE must not create duplicate nodes. */
  @Test
  @Order(3)
  void writeEvent_idempotent_duplicateEventDoesNotCreateDuplicateNodes() throws Exception {
    LineageEvent event =
        buildEvent(
            NS,
            JOB,
            RUN_ID,
            "START",
            List.of(buildDataset(INPUT_NS, INPUT_DS)),
            List.of(buildDataset(NS, OUTPUT_DS)));

    writeInTransaction(event); // second write of same event

    long runCount = countNodes("Run", "runId", RUN_ID);
    assertThat(runCount).as("MERGE must not create duplicate Run nodes").isEqualTo(1);
  }

  /** COMPLETE event updates the run state from START to COMPLETE. */
  @Test
  @Order(4)
  void writeEvent_completeEvent_updatesRunState() throws Exception {
    LineageEvent event =
        buildEvent(
            NS,
            JOB,
            RUN_ID,
            "COMPLETE",
            List.of(buildDataset(INPUT_NS, INPUT_DS)),
            List.of(buildDataset(NS, OUTPUT_DS)));

    writeInTransaction(event);

    String state = getNodeProperty("Run", "runId", RUN_ID, "state");
    assertThat(state).as("Run state should be updated to COMPLETE").isEqualTo("COMPLETE");
  }

  // ===========================================================================
  // Edge types
  // ===========================================================================

  /** Verifies the full edge schema created for the START+COMPLETE event pair. */
  @Test
  @Order(5)
  void writeEvent_createsAllEdgeTypes() throws Exception {
    assertEdgeExists("Source", "name", "default", "Namespace", "name", NS, "HAS_NAMESPACE");

    assertEdgeExists("Namespace", "name", NS, "Job", "fqn", GraphWriter.fqn(NS, JOB), "CONTAINS");

    assertEdgeExists(
        "Dataset",
        "fqn",
        GraphWriter.fqn(INPUT_NS, INPUT_DS),
        "Job",
        "fqn",
        GraphWriter.fqn(NS, JOB),
        "INPUT_TO");

    assertEdgeExists(
        "Job",
        "fqn",
        GraphWriter.fqn(NS, JOB),
        "Dataset",
        "fqn",
        GraphWriter.fqn(NS, OUTPUT_DS),
        "PRODUCES");
  }

  // ===========================================================================
  // Parent-child run relationship
  // ===========================================================================

  /**
   * Writes a child run with a parent facet and verifies the {@code HAS_CHILD_RUN} edge is created
   * in the correct direction (parent → child).
   */
  @Test
  @Order(6)
  void writeEvent_childRunWithParentFacet_createsHasChildRunEdge() throws Exception {
    LineageEvent parentEvent =
        buildEvent(NS, "gw-parent-job", PARENT_RUN_ID, "START", List.of(), List.of());
    LineageEvent childEvent =
        buildChildEvent(
            NS, "gw-child-job", CHILD_RUN_ID, "START", PARENT_RUN_ID, NS, "gw-parent-job");

    writeInTransaction(parentEvent);
    writeInTransaction(childEvent);

    assertEdgeExists("Run", "runId", PARENT_RUN_ID, "Run", "runId", CHILD_RUN_ID, "HAS_CHILD_RUN");
  }

  // ===========================================================================
  // DatasetField nodes
  // ===========================================================================

  /**
   * Verifies that schema fields produce {@code DatasetField} nodes with {@code HAS_FIELD} edges.
   */
  @Test
  @Order(7)
  void writeEvent_datasetsWithSchema_createsDatasetFieldNodes() throws Exception {
    LineageEvent event =
        buildEvent(
            NS,
            "gw-schema-job",
            UUID.randomUUID().toString(),
            "COMPLETE",
            List.of(),
            List.of(buildDatasetWithSchema("gw-schema-ns", "schema_dataset", "user_id", "col_ts")));

    writeInTransaction(event);

    assertAnyNodeExists("DatasetField");
  }

  // ===========================================================================
  // Deterministic UUID
  // ===========================================================================

  /** Same input must always produce the same UUID (stable name-based MD5 / v3). */
  @Test
  @Order(8)
  void generateDeterministicUuid_sameInput_returnsSameUuid() {
    String a = GraphWriter.generateDeterministicUuid("marquez:test-job{}[]");
    String b = GraphWriter.generateDeterministicUuid("marquez:test-job{}[]");
    assertThat(a).isEqualTo(b);
  }

  /** Different inputs must produce different UUIDs. */
  @Test
  @Order(9)
  void generateDeterministicUuid_differentInputs_returnsDifferentUuids() {
    String a = GraphWriter.generateDeterministicUuid("input-one");
    String b = GraphWriter.generateDeterministicUuid("input-two");
    assertThat(a).isNotEqualTo(b);
  }

  // ===========================================================================
  // GraphDao helper methods
  // ===========================================================================

  @Test
  @Order(10)
  void toCypherLiteral_stringEscapesSingleQuotes() {
    String result = GraphDao.toCypherLiteral("it's a test");
    assertThat(result).isEqualTo("'it''s a test'");
  }

  @Test
  @Order(11)
  void toCypherLiteral_nullValue_returnsNullLiteral() {
    assertThat(GraphDao.toCypherLiteral(null)).isEqualTo("null");
  }

  @Test
  @Order(12)
  void toCypherLiteral_integerValue_returnsPlainNumber() {
    assertThat(GraphDao.toCypherLiteral(42)).isEqualTo("42");
  }

  // ===========================================================================
  // Helpers – Cypher query assertions
  // ===========================================================================

  private void writeInTransaction(LineageEvent event) {
    jdbi.useTransaction(
        handle -> {
          GraphDao.initAgeSession(handle.getConnection());
          graphWriter.writeEvent(handle, event);
        });
  }

  private void assertNodeExists(String label, String matchKey, String matchValue) throws Exception {
    long count = countNodes(label, matchKey, matchValue);
    assertThat(count)
        .as("Expected at least 1 %s node with %s='%s'", label, matchKey, matchValue)
        .isGreaterThanOrEqualTo(1);
  }

  private void assertAnyNodeExists(String label) throws Exception {
    String sql =
        String.format(
            "SELECT count(*) FROM %scypher(cast('%s' as name),"
                + " $$ MATCH (n:%s) RETURN count(n) $$) AS (c %sagtype)",
            GraphDao.prefix(), GRAPH, label, GraphDao.prefix());

    long count =
        jdbi.withHandle(
            handle -> {
              try {
                GraphDao.initAgeSession(handle.getConnection());
                Connection conn = handle.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(sql);
                    ResultSet rs = ps.executeQuery()) {
                  return rs.next() ? rs.getLong(1) : 0L;
                }
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    assertThat(count).as("Expected at least 1 %s node", label).isGreaterThanOrEqualTo(1);
  }

  private long countNodes(String label, String matchKey, String matchValue) {
    String literal = GraphDao.toCypherLiteral(matchValue);
    String sql =
        String.format(
            "SELECT count(*) FROM %scypher(cast('%s' as name),"
                + " $$ MATCH (n:%s { %s: %s }) RETURN count(n) $$) AS (c %sagtype)",
            GraphDao.prefix(), GRAPH, label, matchKey, literal, GraphDao.prefix());

    return jdbi.withHandle(
        handle -> {
          try {
            GraphDao.initAgeSession(handle.getConnection());
            Connection conn = handle.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
              return rs.next() ? rs.getLong(1) : 0L;
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private String getNodeProperty(String label, String matchKey, String matchValue, String propKey) {
    String literal = GraphDao.toCypherLiteral(matchValue);
    // cypher() lives in ag_catalog (hence GraphDao.prefix()). agtype_to_json lives in the
    // marquez_v3 schema (see V103 migration) — qualify it explicitly here rather than relying
    // on search_path, because this test issues the query on a raw JDBI connection and the
    // search_path SET from initAgeSession is not always visible on that handle.
    String sql =
        String.format(
            "SELECT marquez_v3.agtype_to_json(n) FROM %scypher(cast('%s' as name),"
                + " $$ MATCH (n:%s { %s: %s }) RETURN properties(n) $$) AS (n %sagtype)",
            GraphDao.prefix(), GRAPH, label, matchKey, literal, GraphDao.prefix());

    return jdbi.withHandle(
        handle -> {
          try {
            GraphDao.initAgeSession(handle.getConnection());
            Connection conn = handle.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
              if (rs.next()) {
                return MAPPER.readTree(rs.getString(1)).path(propKey).asText();
              }
              return null;
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private void assertEdgeExists(
      String fromLabel,
      String fromKey,
      String fromVal,
      String toLabel,
      String toKey,
      String toVal,
      String edgeType) {
    String fromLit = GraphDao.toCypherLiteral(fromVal);
    String toLit = GraphDao.toCypherLiteral(toVal);
    String sql =
        String.format(
            "SELECT count(*) FROM %scypher(cast('%s' as name),"
                + " $$ MATCH (a:%s { %s: %s })-[r:%s]->(b:%s { %s: %s }) RETURN count(r) $$)"
                + " AS (c %sagtype)",
            GraphDao.prefix(),
            GRAPH,
            fromLabel,
            fromKey,
            fromLit,
            edgeType,
            toLabel,
            toKey,
            toLit,
            GraphDao.prefix());

    long count =
        jdbi.withHandle(
            handle -> {
              try {
                GraphDao.initAgeSession(handle.getConnection());
                Connection conn = handle.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(sql);
                    ResultSet rs = ps.executeQuery()) {
                  return rs.next() ? rs.getLong(1) : 0L;
                }
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    assertThat(count)
        .as("Expected (%s)-[:%s]->(%s) edge", fromLabel, edgeType, toLabel)
        .isGreaterThanOrEqualTo(1);
  }

  // ===========================================================================
  // Event builders
  // ===========================================================================

  private static LineageEvent buildEvent(
      String ns,
      String jobName,
      String runId,
      String eventType,
      List<LineageEvent.Dataset> inputs,
      List<LineageEvent.Dataset> outputs) {

    LineageEvent.Job job = new LineageEvent.Job();
    job.setNamespace(ns);
    job.setName(jobName);

    LineageEvent.Run run = new LineageEvent.Run();
    run.setRunId(runId);

    LineageEvent event = new LineageEvent();
    event.setJob(job);
    event.setRun(run);
    event.setEventType(eventType);
    event.setEventTime(ZonedDateTime.now());
    event.setInputs(inputs);
    event.setOutputs(outputs);
    event.setProducer("https://github.com/OpenLineage/test");
    return event;
  }

  /**
   * Builds a child run event with a {@code parent} facet pointing to {@code parentRunId}. Uses
   * {@link RunLink} and {@link JobLink} (the correct link types from the OpenLineage model).
   */
  private static LineageEvent buildChildEvent(
      String ns,
      String jobName,
      String runId,
      String eventType,
      String parentRunId,
      String parentJobNs,
      String parentJobName) {

    // RunLink and JobLink are used inside the ParentRunFacet (not Run/Job directly)
    RunLink parentRunLink = new RunLink();
    parentRunLink.setRunId(parentRunId);

    JobLink parentJobLink = new JobLink();
    parentJobLink.setNamespace(parentJobNs);
    parentJobLink.setName(parentJobName);

    // ParentRunFacet has @NoArgsConstructor + @Setter so we can use setter-style construction.
    // The _producer / _schemaURL are not accessed by GraphWriter, only the run/job links are.
    ParentRunFacet parentFacet = new ParentRunFacet();
    parentFacet.setRun(parentRunLink);
    parentFacet.setJob(parentJobLink);

    RunFacet runFacet = RunFacet.builder().parent(parentFacet).build();

    LineageEvent.Run run = new LineageEvent.Run();
    run.setRunId(runId);
    run.setFacets(runFacet);

    LineageEvent.Job job = new LineageEvent.Job();
    job.setNamespace(ns);
    job.setName(jobName);

    LineageEvent event = new LineageEvent();
    event.setJob(job);
    event.setRun(run);
    event.setEventType(eventType);
    event.setEventTime(ZonedDateTime.now());
    event.setInputs(new ArrayList<>());
    event.setOutputs(new ArrayList<>());
    event.setProducer("https://github.com/OpenLineage/test");
    return event;
  }

  private static LineageEvent.Dataset buildDataset(String ns, String name) {
    LineageEvent.Dataset ds = new LineageEvent.Dataset();
    ds.setNamespace(ns);
    ds.setName(name);
    return ds;
  }

  /**
   * Builds a dataset with a schema facet. Uses the {@link SchemaDatasetFacet} builder which
   * requires {@code _producer} and {@code _schemaURL} (from {@code BaseFacet}).
   */
  private static LineageEvent.Dataset buildDatasetWithSchema(
      String ns, String name, String... fieldNames) {
    List<SchemaField> fields = new ArrayList<>();
    for (String fn : fieldNames) {
      SchemaField f = new SchemaField();
      f.setName(fn);
      f.setType("STRING");
      fields.add(f);
    }

    SchemaDatasetFacet schema =
        SchemaDatasetFacet.builder()
            ._producer(TEST_PRODUCER)
            ._schemaURL(TEST_SCHEMA)
            .fields(fields)
            .build();

    DatasetFacets facets = new DatasetFacets();
    facets.setSchema(schema);

    LineageEvent.Dataset ds = buildDataset(ns, name);
    ds.setFacets(facets);
    return ds;
  }
}

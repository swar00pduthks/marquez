/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service;

import static marquez.db.LineageTestUtils.NAMESPACE;
import static marquez.db.LineageTestUtils.newDatasetFacet;
import static marquez.db.LineageTestUtils.writeDownstreamLineage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import marquez.api.JdbiUtils;
import marquez.common.models.DatasetId;
import marquez.common.models.DatasetName;
import marquez.common.models.InputDatasetVersion;
import marquez.common.models.JobId;
import marquez.common.models.JobName;
import marquez.common.models.NamespaceName;
import marquez.common.models.RunId;
import marquez.common.models.RunState;
import marquez.db.DatasetDao;
import marquez.db.JobDao;
import marquez.db.LineageDao;
import marquez.db.LineageTestUtils;
import marquez.db.LineageTestUtils.DatasetConsumerJob;
import marquez.db.LineageTestUtils.JobLineage;
import marquez.db.OpenLineageDao;
import marquez.db.RunDao;
import marquez.db.models.UpdateLineageRow;
import marquez.jdbi.MarquezJdbiExternalPostgresExtension;
import marquez.service.LineageService.UpstreamRunLineage;
import marquez.service.models.Edge;
import marquez.service.models.Job;
import marquez.service.models.JobData;
import marquez.service.models.Lineage;
import marquez.service.models.LineageEvent;
import marquez.service.models.LineageEvent.Dataset;
import marquez.service.models.LineageEvent.JobFacet;
import marquez.service.models.LineageEvent.JobTypeJobFacet;
import marquez.service.models.LineageEvent.SchemaField;
import marquez.service.models.Node;
import marquez.service.models.NodeId;
import marquez.service.models.NodeType;
import marquez.service.models.Run;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.Condition;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.ObjectAssert;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
public class LineageServiceTest {

  private static LineageDao lineageDao;
  private static LineageService lineageService;
  private static OpenLineageDao openLineageDao;
  private static DatasetDao datasetDao;
  private static JobDao jobDao;
  private static DenormalizedLineageService denormalizedLineageService;

  private final Dataset dataset =
      new Dataset(
          NAMESPACE,
          "commonDataset",
          newDatasetFacet(
              new SchemaField("firstname", "string", "the first name"),
              new SchemaField("lastname", "string", "the last name"),
              new SchemaField("birthdate", "date", "the date of birth")));
  private final JobFacet jobFacet = JobFacet.builder().build();
  static Jdbi jdbi;

  @BeforeAll
  public static void setUpOnce(Jdbi jdbi) {
    LineageServiceTest.jdbi = jdbi;
    lineageDao = jdbi.onDemand(LineageDao.class);
    lineageService =
        new LineageService(lineageDao, jdbi.onDemand(JobDao.class), jdbi.onDemand(RunDao.class));
    openLineageDao = jdbi.onDemand(OpenLineageDao.class);
    datasetDao = jdbi.onDemand(DatasetDao.class);
    jobDao = jdbi.onDemand(JobDao.class);
    denormalizedLineageService = new DenormalizedLineageService(jdbi);
  }

  @AfterEach
  public void tearDown(Jdbi jdbi) {
    JdbiUtils.cleanDatabase(jdbi);
  }

  @Test
  public void testLineage() {
    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));
    List<JobLineage> jobRows =
        writeDownstreamLineage(
            openLineageDao,
            new LinkedList<>(
                Arrays.asList(
                    new DatasetConsumerJob("readJob", 20, Optional.of("outputData")),
                    new DatasetConsumerJob("downstreamJob", 1, Optional.of("outputData2")),
                    new DatasetConsumerJob("finalConsumer", 1, Optional.empty()))),
            jobFacet,
            dataset);

    UpdateLineageRow secondRun =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));
    writeDownstreamLineage(
        openLineageDao,
        new LinkedList<>(
            Arrays.asList(
                new DatasetConsumerJob("newReadJob", 5, Optional.of("outputData3")),
                new DatasetConsumerJob("newDownstreamJob", 1, Optional.empty()))),
        jobFacet,
        dataset);
    String jobName = writeJob.getJob().getName();
    Lineage lineage =
        lineageService.lineage(
            NodeId.of(new NamespaceName(NAMESPACE), new JobName(jobName)), 2, false);

    // 1 writeJob           + 1 commonDataset
    // 20 readJob           + 20 outputData
    // 20 downstreamJob     + 20 outputData2
    // 5 newReadJob         + 5 outputData3
    // 5 newDownstreamJob   + 0
    assertThat(lineage.getGraph())
        .hasSize(97) // 51 jobs + 46 datasets
        .areExactly(51, new Condition<>(n -> n.getType().equals(NodeType.JOB), "job"))
        .areExactly(46, new Condition<>(n -> n.getType().equals(NodeType.DATASET), "dataset"))
        // finalConsumer job is out of the depth range
        .filteredOn(
            node ->
                node.getType().equals(NodeType.JOB)
                    && node.getId().asJobId().getName().getValue().contains("finalConsumer"))
        .isEmpty();

    // assert the second run of writeJob is returned
    AbstractObjectAssert<?, Run> runAssert =
        assertThat(lineage.getGraph())
            .filteredOn(
                node -> node.getType().equals(NodeType.JOB) && jobNameEquals(node, "writeJob"))
            .hasSize(1)
            .first()
            .extracting(
                n -> ((JobData) n.getData()).getLatestRun(),
                InstanceOfAssertFactories.optional(Run.class))
            .isPresent()
            .get();
    runAssert.extracting(r -> r.getId().getValue()).isEqualTo(secondRun.getRun().getUuid());
    runAssert
        .extracting(
            Run::getInputDatasetVersions, InstanceOfAssertFactories.list(InputDatasetVersion.class))
        .hasSize(0);

    // check the output edges for the commonDataset node
    assertThat(lineage.getGraph())
        .filteredOn(
            node ->
                node.getType().equals(NodeType.DATASET)
                    && node.getId().asDatasetId().getName().getValue().equals("commonDataset"))
        .first()
        .extracting(Node::getOutEdges, InstanceOfAssertFactories.iterable(Edge.class))
        .hasSize(25)
        .extracting(e -> e.getDestination().asJobId().getName())
        .allMatch(n -> n.getValue().matches(".*eadJob\\d+<-commonDataset"));

    assertThat(lineage.getGraph())
        .filteredOn(
            n ->
                n.getType().equals(NodeType.JOB)
                    && jobNameEquals(n, "downstreamJob0<-outputData<-readJob0<-commonDataset"))
        .hasSize(1)
        .first()
        .extracting(Node::getInEdges, InstanceOfAssertFactories.iterable(Edge.class))
        .hasSize(1)
        .first()
        .extracting(Edge::getOrigin)
        .isEqualTo(
            NodeId.of(
                new NamespaceName(NAMESPACE),
                new DatasetName("outputData<-readJob0<-commonDataset")));

    List<RunState> runStates = new ArrayList<>();
    Collections.addAll(runStates, RunState.values());

    List<Job> jobs = jobDao.findAllWithRun(NAMESPACE, runStates, 1000, 0);
    jobs =
        jobs.stream()
            .filter(j -> j.getName().getValue().contains("newDownstreamJob"))
            .collect(Collectors.toList());
    assertTrue(jobs.size() > 0);
    Job job = jobs.get(0);
    assertTrue(job.getLatestRun().isPresent());
    UpstreamRunLineage upstreamLineage =
        lineageService.upstream(job.getLatestRun().get().getId(), 10);
    assertThat(upstreamLineage.runs()).size().isEqualTo(3);
    assertThat(upstreamLineage.runs().get(0).job().name().getValue())
        .matches("newDownstreamJob.*<-outputData.*<-newReadJob.*<-commonDataset");
    assertThat(upstreamLineage.runs().get(0).inputs().get(0).name().getValue())
        .matches("outputData.*<-newReadJob.*<-commonDataset");
    assertThat(upstreamLineage.runs().get(1).job().name().getValue())
        .matches("newReadJob.*<-commonDataset");
    assertThat(upstreamLineage.runs().get(1).inputs().get(0).name().getValue())
        .isEqualTo("commonDataset");
    assertThat(upstreamLineage.runs().get(2).job().name().getValue()).isEqualTo("writeJob");
  }

  @Test
  public void testLineageWithDeletedDataset() {
    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));
    List<JobLineage> jobRows =
        writeDownstreamLineage(
            openLineageDao,
            new LinkedList<>(
                Arrays.asList(
                    new DatasetConsumerJob("readJob", 20, Optional.of("outputData")),
                    new DatasetConsumerJob("downstreamJob", 1, Optional.of("outputData2")),
                    new DatasetConsumerJob("finalConsumer", 1, Optional.empty()))),
            jobFacet,
            dataset);
    UpdateLineageRow secondRun =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));
    writeDownstreamLineage(
        openLineageDao,
        new LinkedList<>(
            Arrays.asList(
                new DatasetConsumerJob("newReadJob", 5, Optional.of("outputData3")),
                new DatasetConsumerJob("newDownstreamJob", 1, Optional.empty()))),
        jobFacet,
        dataset);

    datasetDao.delete(NAMESPACE, "commonDataset");

    String jobName = writeJob.getJob().getName();
    Lineage lineage =
        lineageService.lineage(
            NodeId.of(new NamespaceName(NAMESPACE), new JobName(jobName)), 2, false);

    // 1 writeJob           + 0 commonDataset is hidden
    // 20 readJob           + 20 outputData
    // 20 downstreamJob     + 20 outputData2
    // 5 newReadJob         + 5 outputData3
    // 5 newDownstreamJob   + 0
    assertThat(lineage.getGraph())
        .hasSize(96) // 51 jobs + 45 datasets - one is hidden
        .areExactly(51, new Condition<>(n -> n.getType().equals(NodeType.JOB), "job"))
        .areExactly(45, new Condition<>(n -> n.getType().equals(NodeType.DATASET), "dataset"))
        // finalConsumer job is out of the depth range
        .filteredOn(
            node ->
                node.getType().equals(NodeType.JOB)
                    && node.getId().asJobId().getName().getValue().contains("finalConsumer"))
        .isEmpty();

    // assert the second run of writeJob is returned
    AbstractObjectAssert<?, Run> runAssert =
        assertThat(lineage.getGraph())
            .filteredOn(
                node -> node.getType().equals(NodeType.JOB) && jobNameEquals(node, "writeJob"))
            .hasSize(1)
            .first()
            .extracting(
                n -> ((JobData) n.getData()).getLatestRun(),
                InstanceOfAssertFactories.optional(Run.class))
            .isPresent()
            .get();
    runAssert.extracting(r -> r.getId().getValue()).isEqualTo(secondRun.getRun().getUuid());
    runAssert
        .extracting(
            Run::getInputDatasetVersions, InstanceOfAssertFactories.list(InputDatasetVersion.class))
        .hasSize(0);

    // check the output edges for the commonDataset node
    assertThat(lineage.getGraph())
        .filteredOn(
            node ->
                node.getType().equals(NodeType.DATASET)
                    && node.getId().asDatasetId().getName().getValue().equals("commonDataset"))
        .isEmpty();

    jobDao.delete(NAMESPACE, "downstreamJob0<-outputData<-readJob0<-commonDataset");

    lineage =
        lineageService.lineage(
            NodeId.of(new NamespaceName(NAMESPACE), new JobName(jobName)), 2, false);

    // 1 writeJob           + 0 commonDataset is hidden
    // 20 readJob           + 20 outputData
    // 20 downstreamJob     + 20 outputData2
    // 5 newReadJob         + 5 outputData3
    // 5 newDownstreamJob   + 0
    assertThat(lineage.getGraph())
        .hasSize(
            94) // 51 jobs + 45 datasets - one dataset is hidden + one job that produces dataset is
        // hidden
        .areExactly(50, new Condition<>(n -> n.getType().equals(NodeType.JOB), "job"))
        .areExactly(44, new Condition<>(n -> n.getType().equals(NodeType.DATASET), "dataset"));

    // assert that readJob is hidden
    assertThat(lineage.getGraph())
        .filteredOn(
            n ->
                n.getType().equals(NodeType.JOB)
                    && jobNameEquals(n, "downstreamJob0<-outputData<-readJob0<-commonDataset"))
        .isEmpty();
  }

  @Test
  public void testLineageWithNoDatasets() {
    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao, "writeJob", "COMPLETE", jobFacet, Arrays.asList(), Arrays.asList());
    Lineage lineage =
        lineageService.lineage(
            NodeId.of(new NamespaceName(NAMESPACE), new JobName(writeJob.getJob().getName())),
            5,
            false);
    assertThat(lineage.getGraph())
        .hasSize(1)
        .first()
        .satisfies(n -> n.getId().asJobId().getName().getValue().equals("writeJob"));
  }

  @Test
  public void testLineageWithWithCycle() {
    Dataset intermediateDataset =
        new Dataset(
            NAMESPACE,
            "intermediateDataset",
            newDatasetFacet(
                new SchemaField("firstname", "string", "the first name"),
                new SchemaField("birthdate", "date", "the date of birth")));
    LineageTestUtils.createLineageRow(
        openLineageDao,
        "writeJob",
        "COMPLETE",
        jobFacet,
        Arrays.asList(dataset),
        Arrays.asList(intermediateDataset));

    Dataset finalDataset =
        new Dataset(
            NAMESPACE,
            "finalDataset",
            newDatasetFacet(
                new SchemaField("firstname", "string", "the first name"),
                new SchemaField("lastname", "string", "the last name")));
    UpdateLineageRow intermediateJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "intermediateJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(intermediateDataset),
            Arrays.asList(finalDataset));

    LineageTestUtils.createLineageRow(
        openLineageDao,
        "cycleJob",
        "COMPLETE",
        jobFacet,
        Arrays.asList(finalDataset),
        Arrays.asList(dataset));
    Lineage lineage =
        lineageService.lineage(
            NodeId.of(
                new NamespaceName(NAMESPACE), new JobName(intermediateJob.getJob().getName())),
            5,
            false);
    assertThat(lineage.getGraph()).extracting(Node::getId).hasSize(6);
    ObjectAssert<Node> datasetNode =
        assertThat(lineage.getGraph())
            .filteredOn(
                n1 ->
                    n1.getId().isDatasetType()
                        && n1.getId().asDatasetId().getName().getValue().equals("commonDataset"))
            .hasSize(1)
            .first();
    datasetNode
        .extracting(Node::getInEdges, InstanceOfAssertFactories.iterable(Edge.class))
        .hasSize(1)
        .first()
        .extracting(Edge::getOrigin)
        .matches(n -> n.isJobType() && n.asJobId().getName().getValue().equals("cycleJob"));

    datasetNode
        .extracting(Node::getOutEdges, InstanceOfAssertFactories.iterable(Edge.class))
        .hasSize(1)
        .first()
        .extracting(Edge::getDestination)
        .matches(n -> n.isJobType() && n.asJobId().getName().getValue().equals("writeJob"));
  }

  @Test
  public void testGetLineageJobRunTwice() {
    Dataset input = Dataset.builder().name("input-dataset").namespace(NAMESPACE).build();
    Dataset output = Dataset.builder().name("output-dataset").namespace(NAMESPACE).build();
    UUID runId = UUID.randomUUID();

    // (1) Run batch job which outputs input-dataset
    LineageTestUtils.createLineageRow(
        openLineageDao,
        "someJob",
        runId,
        "START",
        jobFacet,
        Arrays.asList(input),
        Collections.emptyList(),
        null,
        ImmutableMap.of());

    LineageTestUtils.createLineageRow(
        openLineageDao,
        "someJob",
        runId,
        "COMPLETE",
        jobFacet,
        Collections.emptyList(),
        Arrays.asList(output),
        null,
        ImmutableMap.of());

    // (2) Rerun it
    LineageTestUtils.createLineageRow(
        openLineageDao,
        "someJob",
        runId,
        "START",
        jobFacet,
        Arrays.asList(input),
        Collections.emptyList(),
        null,
        ImmutableMap.of());

    LineageTestUtils.createLineageRow(
        openLineageDao,
        "someJob",
        runId,
        "COMPLETE",
        jobFacet,
        Collections.emptyList(),
        Arrays.asList(output),
        null,
        ImmutableMap.of());

    // (4) lineage on output dataset shall be same as lineage on input dataset
    Lineage lineageFromInput =
        lineageService.lineage(
            NodeId.of(
                new DatasetId(new NamespaceName(NAMESPACE), new DatasetName("input-dataset"))),
            5,
            false);

    Lineage lineageFromOutput =
        lineageService.lineage(
            NodeId.of(
                new DatasetId(new NamespaceName(NAMESPACE), new DatasetName("output-dataset"))),
            5,
            false);

    assertThat(lineageFromInput.getGraph()).hasSize(3); // 2 datasets + 1 job
    assertThat(lineageFromInput.getGraph()).isEqualTo(lineageFromOutput.getGraph());
  }

  @Test
  public void testGetLineageForRunningStreamingJob() {
    Dataset input = Dataset.builder().name("input-dataset").namespace(NAMESPACE).build();
    Dataset output = Dataset.builder().name("output-dataset").namespace(NAMESPACE).build();

    // (1) Run batch job which outputs input-dataset
    LineageTestUtils.createLineageRow(
        openLineageDao,
        "someInputBatchJob",
        "COMPLETE",
        jobFacet,
        Collections.emptyList(),
        Arrays.asList(input));

    // (2) Run streaming job on the reading output of this job
    LineageTestUtils.createLineageRow(
        openLineageDao,
        "streamingjob",
        "RUNNING",
        JobFacet.builder()
            .jobType(JobTypeJobFacet.builder().processingType("STREAMING").build())
            .build(),
        Arrays.asList(input),
        Arrays.asList(output));

    // (3) Run batch job that reads output of streaming job (Note: streaming job is still running)
    LineageTestUtils.createLineageRow(
        openLineageDao,
        "someOutputBatchJob",
        "COMPLETE",
        jobFacet,
        Arrays.asList(output),
        Collections.emptyList());

    // (4) lineage on output dataset shall be same as lineage on input dataset
    Lineage lineageFromInput =
        lineageService.lineage(
            NodeId.of(
                new DatasetId(new NamespaceName(NAMESPACE), new DatasetName("input-dataset"))),
            5,
            false);

    Lineage lineageFromOutput =
        lineageService.lineage(
            NodeId.of(
                new DatasetId(new NamespaceName(NAMESPACE), new DatasetName("output-dataset"))),
            5,
            false);

    assertThat(lineageFromInput.getGraph()).hasSize(5); // 2 datasets + 3 jobs
    assertThat(lineageFromInput.getGraph()).isEqualTo(lineageFromOutput.getGraph());
  }

  @Test
  public void testGetLineageForCompleteStreamingJob() {
    Dataset input = Dataset.builder().name("input-dataset").namespace(NAMESPACE).build();
    Dataset output = Dataset.builder().name("output-dataset").namespace(NAMESPACE).build();

    LineageTestUtils.createLineageRow(
        openLineageDao,
        "streamingjob",
        "RUNNING",
        JobFacet.builder()
            .jobType(JobTypeJobFacet.builder().processingType("STREAMING").build())
            .build(),
        Arrays.asList(input),
        Arrays.asList(output));

    LineageTestUtils.createLineageRow(
        openLineageDao,
        "streamingjob",
        "COMPLETE",
        JobFacet.builder()
            .jobType(JobTypeJobFacet.builder().processingType("STREAMING").build())
            .build(),
        Collections.emptyList(),
        Collections.emptyList());

    Lineage lineage =
        lineageService.lineage(
            NodeId.of(
                new DatasetId(new NamespaceName(NAMESPACE), new DatasetName("output-dataset"))),
            5,
            false);

    assertThat(lineage.getGraph()).hasSize(3); // 1 job + 2 datasets
  }

  @Test
  public void testLineageForOrphanedDataset() {
    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));

    NodeId datasetNodeId =
        NodeId.of(new NamespaceName(dataset.getNamespace()), new DatasetName(dataset.getName()));
    Lineage lineage = lineageService.lineage(datasetNodeId, 2, false);
    assertThat(lineage.getGraph())
        .hasSize(2)
        .extracting(Node::getId)
        .containsExactlyInAnyOrder(
            NodeId.of(new JobId(new NamespaceName(NAMESPACE), new JobName("writeJob"))),
            datasetNodeId);

    UpdateLineageRow updatedWriteJob =
        LineageTestUtils.createLineageRow(
            openLineageDao, "writeJob", "COMPLETE", jobFacet, Arrays.asList(), Arrays.asList());

    lineage = lineageService.lineage(datasetNodeId, 2, false);
    assertThat(lineage.getGraph())
        .hasSize(1)
        .extracting(Node::getId)
        .containsExactlyInAnyOrder(datasetNodeId);
  }

  private boolean jobNameEquals(Node node, String writeJob) {
    return node.getId().asJobId().getName().getValue().equals(writeJob);
  }

  @Test
  public void testSymlinkDatasetLineage() {
    // (1) Create symlink facet for our main dataset
    Map<String, Object> symlink = new HashMap<>();
    Map<String, Object> symlinkInfo = new HashMap<>();
    Map<String, Object> symlinkIdentifiers = new HashMap<>();
    symlinkIdentifiers.put("name", "symlinkDataset");
    symlinkIdentifiers.put("namespace", NAMESPACE);
    symlinkIdentifiers.put("type", "DB_TABLE");
    symlinkInfo.put("producer", "https://github.com/OpenLineage/producer/");
    symlinkInfo.put("schemaURL", "https://openlineage.io/schema/url/");
    symlinkInfo.put("identifiers", symlinkIdentifiers);
    symlink.put("symlinks", symlinkInfo);

    // (2) Create main dataset with a symlink
    Dataset mainDataset =
        new Dataset(
            NAMESPACE,
            "mainDataset",
            newDatasetFacet(symlink, new SchemaField("firstname", "string", "the first name")));

    // (3) Create the symlink dataset
    Dataset symlinkDataset =
        new Dataset(
            NAMESPACE,
            "symlinkDataset",
            newDatasetFacet(new SchemaField("firstname", "string", "the first name")));

    // (3) Create a job with the main dataset
    UpdateLineageRow firstJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "firstJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(mainDataset),
            Arrays.asList());

    // (4) Create a job with the symlink dataset
    UpdateLineageRow secondJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "secondJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(symlinkDataset),
            Arrays.asList());

    // (5) We expect the first and second job linked together because the main
    // and symlink dataset are in fact the same dataset
    Lineage lineage =
        lineageService.lineage(
            NodeId.of(
                new DatasetId(new NamespaceName(NAMESPACE), new DatasetName("symlinkDataset"))),
            5,
            false);

    assertThat(lineage.getGraph()).hasSize(2);
  }

  @Test
  public void testRunLineageBasic() {
    // Create a run with input and output datasets
    Dataset inputDataset = new Dataset(NAMESPACE, "inputDataset", newDatasetFacet());
    Dataset outputDataset = new Dataset(NAMESPACE, "outputDataset", newDatasetFacet());

    UpdateLineageRow run =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "testJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(inputDataset),
            Arrays.asList(outputDataset));

    UUID runUuid = run.getRun().getUuid();

    // Populate denormalized tables for the run
    denormalizedLineageService.populateLineageForRun(runUuid);

    // Test run lineage (calls getRunLineage() method)
    Lineage lineage = lineageService.lineage(NodeId.of(new RunId(runUuid)), 2, false);

    // Verify lineage contains the run and its datasets
    assertThat(lineage.getGraph()).isNotEmpty();

    // Check that we have nodes for the run, input dataset, and output dataset
    List<NodeId> nodeIds =
        lineage.getGraph().stream().map(Node::getId).collect(Collectors.toList());

    // Verify we have the run node
    assertThat(nodeIds).contains(NodeId.of(new RunId(runUuid)));

    // Verify we have dataset nodes (they will include version UUIDs)
    assertThat(nodeIds)
        .anyMatch(nodeId -> nodeId.getValue().startsWith("dataset:namespace:inputDataset#"));
    assertThat(nodeIds)
        .anyMatch(nodeId -> nodeId.getValue().startsWith("dataset:namespace:outputDataset#"));
  }

  @Test
  public void testRunLineageWithFacets() {
    // Create a run with facets to test the SQL queries with facets
    Dataset inputDataset = new Dataset(NAMESPACE, "inputDataset", newDatasetFacet());
    Dataset outputDataset = new Dataset(NAMESPACE, "outputDataset", newDatasetFacet());

    UpdateLineageRow run =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "testJobWithFacets",
            "COMPLETE",
            jobFacet,
            Arrays.asList(inputDataset),
            Arrays.asList(outputDataset));

    UUID runUuid = run.getRun().getUuid();

    // Add run facets to test the facets handling in SQL queries
    jdbi.useHandle(
        handle -> {
          handle.execute(
              "INSERT INTO run_facets (created_at, run_uuid, lineage_event_time, lineage_event_type, name, facet) "
                  + "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
              java.time.Instant.now(),
              runUuid,
              java.time.Instant.now(),
              "COMPLETE",
              "test_facet",
              "{\"test\": \"value\"}");
        });

    // Populate denormalized tables for the run
    denormalizedLineageService.populateLineageForRun(runUuid);

    // Test run lineage with facets (calls getRunLineage() method)
    Lineage lineage = lineageService.lineage(NodeId.of(new RunId(runUuid)), 2, false);

    // Verify lineage works with facets
    assertThat(lineage.getGraph()).isNotEmpty();

    // Verify we can find the run node
    Optional<Node> runNode =
        lineage.getGraph().stream()
            .filter(node -> node.getId().equals(NodeId.of(new RunId(runUuid))))
            .findFirst();

    assertThat(runNode).isPresent();
  }

  @Test
  public void testParentRunLineage() {
    // Create parent run
    UpdateLineageRow parentRun =
        LineageTestUtils.createLineageRow(
            openLineageDao, "parentJob", "COMPLETE", jobFacet, Arrays.asList(), Arrays.asList());

    // Create child run with parent relationship
    UpdateLineageRow childRun =
        LineageTestUtils.createLineageRow(
            openLineageDao, "childJob", "COMPLETE", jobFacet, Arrays.asList(), Arrays.asList());

    UUID parentRunUuid = parentRun.getRun().getUuid();
    UUID childRunUuid = childRun.getRun().getUuid();

    // Set parent-child relationship
    jdbi.useHandle(
        handle -> {
          handle.execute(
              "INSERT INTO run_facets (created_at, run_uuid, lineage_event_time, lineage_event_type, name, facet) "
                  + "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
              java.time.Instant.now(),
              childRunUuid,
              java.time.Instant.now(),
              "COMPLETE",
              "parent",
              String.format(
                  "{\"run\": {\"runId\": \"%s\"}, \"job\": {\"namespace\": \"%s\", \"name\": \"parentJob\"}}",
                  parentRunUuid, NAMESPACE));
        });

    // Populate denormalized tables for both runs
    denormalizedLineageService.populateLineageForRun(parentRunUuid);
    denormalizedLineageService.populateLineageForRun(childRunUuid);

    // Test parent run lineage (calls getParentRunLineage() method)
    Lineage parentLineage = lineageService.lineage(NodeId.of(new RunId(parentRunUuid)), 2, true);

    // Verify parent lineage works
    assertThat(parentLineage.getGraph()).isNotEmpty();

    // Verify we can find the parent run node
    Optional<Node> parentNode =
        parentLineage.getGraph().stream()
            .filter(node -> node.getId().equals(NodeId.of(new RunId(parentRunUuid))))
            .findFirst();

    assertThat(parentNode).isPresent();
  }

  @Test
  public void testRunLineageDeepHierarchy() {
    // Create a deep lineage hierarchy to test complex SQL queries
    Dataset level1Dataset = new Dataset(NAMESPACE, "level1Dataset", newDatasetFacet());
    Dataset level2Dataset = new Dataset(NAMESPACE, "level2Dataset", newDatasetFacet());
    Dataset level3Dataset = new Dataset(NAMESPACE, "level3Dataset", newDatasetFacet());

    // Level 1 run
    UpdateLineageRow level1Run =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "level1Job",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(level1Dataset));

    // Level 2 run
    UpdateLineageRow level2Run =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "level2Job",
            "COMPLETE",
            jobFacet,
            Arrays.asList(level1Dataset),
            Arrays.asList(level2Dataset));

    // Level 3 run
    UpdateLineageRow level3Run =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "level3Job",
            "COMPLETE",
            jobFacet,
            Arrays.asList(level2Dataset),
            Arrays.asList(level3Dataset));

    UUID level1RunUuid = level1Run.getRun().getUuid();
    UUID level2RunUuid = level2Run.getRun().getUuid();
    UUID level3RunUuid = level3Run.getRun().getUuid();

    // Populate denormalized tables for all runs
    denormalizedLineageService.populateLineageForRun(level1RunUuid);
    denormalizedLineageService.populateLineageForRun(level2RunUuid);
    denormalizedLineageService.populateLineageForRun(level3RunUuid);

    // Test deep lineage (calls getRunLineage() method with depth > 1)
    Lineage lineage = lineageService.lineage(NodeId.of(new RunId(level1RunUuid)), 3, false);

    // Verify deep lineage works
    assertThat(lineage.getGraph()).isNotEmpty();

    // Verify we have nodes for all levels
    List<NodeId> nodeIds =
        lineage.getGraph().stream().map(Node::getId).collect(Collectors.toList());

    // Verify we have the run node
    assertThat(nodeIds).contains(NodeId.of(new RunId(level1RunUuid)));

    // Verify we have dataset nodes (they will include version UUIDs)
    assertThat(nodeIds)
        .anyMatch(nodeId -> nodeId.getValue().startsWith("dataset:namespace:level1Dataset#"));
    assertThat(nodeIds)
        .anyMatch(nodeId -> nodeId.getValue().startsWith("dataset:namespace:level2Dataset#"));
    assertThat(nodeIds)
        .anyMatch(nodeId -> nodeId.getValue().startsWith("dataset:namespace:level3Dataset#"));
  }

  @Test
  public void testRunLineageEmptyResult() {
    // Test run lineage for non-existent run
    UUID nonExistentRunUuid = UUID.randomUUID();

    // Test run lineage (calls getRunLineage() method)
    Lineage lineage = lineageService.lineage(NodeId.of(new RunId(nonExistentRunUuid)), 2, false);

    // Verify empty result
    assertThat(lineage.getGraph()).isEmpty();
  }

  @Test
  public void testRunLineageWithMultipleFacets() {
    // Create a run with multiple facets to test complex SQL queries
    Dataset inputDataset = new Dataset(NAMESPACE, "inputDataset", newDatasetFacet());
    Dataset outputDataset = new Dataset(NAMESPACE, "outputDataset", newDatasetFacet());

    UpdateLineageRow run =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "testJobWithMultipleFacets",
            "COMPLETE",
            jobFacet,
            Arrays.asList(inputDataset),
            Arrays.asList(outputDataset));

    UUID runUuid = run.getRun().getUuid();

    // Add multiple run facets
    jdbi.useHandle(
        handle -> {
          handle.execute(
              "INSERT INTO run_facets (created_at, run_uuid, lineage_event_time, lineage_event_type, name, facet) "
                  + "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
              java.time.Instant.now(),
              runUuid,
              java.time.Instant.now(),
              "COMPLETE",
              "facet1",
              "{\"test1\": \"value1\"}");

          handle.execute(
              "INSERT INTO run_facets (created_at, run_uuid, lineage_event_time, lineage_event_type, name, facet) "
                  + "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
              java.time.Instant.now(),
              runUuid,
              java.time.Instant.now(),
              "COMPLETE",
              "facet2",
              "{\"test2\": \"value2\"}");
        });

    // Populate denormalized tables for the run
    denormalizedLineageService.populateLineageForRun(runUuid);

    // Test run lineage with multiple facets (calls getRunLineage() method)
    Lineage lineage = lineageService.lineage(NodeId.of(new RunId(runUuid)), 2, false);

    // Verify lineage works with multiple facets
    assertThat(lineage.getGraph()).isNotEmpty();

    // Verify we can find the run node
    Optional<Node> runNode =
        lineage.getGraph().stream()
            .filter(node -> node.getId().equals(NodeId.of(new RunId(runUuid))))
            .findFirst();

    assertThat(runNode).isPresent();
  }

  @Test
  public void testLineageWithIncludeFacetsParameter() {
    // Create jobs with custom run facets
    ImmutableMap<String, Object> sparkFacet =
        ImmutableMap.of(
            "spark_version",
            "3.1.0",
            "spark.properties",
            ImmutableMap.of("spark.executor.memory", "2g"));
    ImmutableMap<String, Object> processingEngineFacet =
        ImmutableMap.of(
            "version", "2.0.0",
            "name", "spark");

    ImmutableMap<String, Object> runFacets =
        ImmutableMap.of(
            "spark", sparkFacet,
            "processing_engine", processingEngineFacet);

    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJobWithFacets",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset),
            null,
            runFacets);

    // Populate denormalized tables
    denormalizedLineageService.populateLineageForRun(writeJob.getRun().getUuid());

    // Test 1: Get lineage without facet filtering (backward compatibility)
    Lineage allFacetsLineage =
        lineageService.lineage(NodeId.of(new RunId(writeJob.getRun().getUuid())), 2, false, null);

    assertThat(allFacetsLineage.getGraph()).isNotEmpty();

    // Find run node and verify it has facets
    Optional<Node> runNodeWithAllFacets =
        allFacetsLineage.getGraph().stream().filter(node -> node.getId().isRunType()).findFirst();
    assertThat(runNodeWithAllFacets).isPresent();
    // Note: Can't directly access facets from Node, but the query should have returned them

    // Test 2: Get lineage with facet filtering to only include nominalTime
    java.util.Set<String> includeFacets = java.util.Set.of("nominalTime");
    Lineage filteredLineage =
        lineageService.lineage(
            NodeId.of(new RunId(writeJob.getRun().getUuid())), 2, false, includeFacets);

    assertThat(filteredLineage.getGraph()).isNotEmpty();

    // Verify graph structure is the same even with filtered facets
    assertThat(filteredLineage.getGraph().size()).isEqualTo(allFacetsLineage.getGraph().size());

    // Test 3: Get lineage with multiple included facets
    java.util.Set<String> multipleIncludeFacets = java.util.Set.of("nominalTime", "spark");
    Lineage multiFilteredLineage =
        lineageService.lineage(
            NodeId.of(new RunId(writeJob.getRun().getUuid())), 2, false, multipleIncludeFacets);

    assertThat(multiFilteredLineage.getGraph()).isNotEmpty();
    assertThat(multiFilteredLineage.getGraph().size())
        .isEqualTo(allFacetsLineage.getGraph().size());
  }

  @Test
  public void testLineageWithAggregateToParentRunAndIncludeFacets() {
    // Create parent run with facets
    ImmutableMap<String, Object> parentRunFacets =
        ImmutableMap.of(
            "parent_facet", ImmutableMap.of("value", "parent"),
            "spark", ImmutableMap.of("spark_version", "3.2.0"));

    UUID parentRunId = UUID.randomUUID();
    UpdateLineageRow parentJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "parentJob",
            parentRunId,
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset),
            null,
            parentRunFacets);

    // Create child run
    LineageEvent.ParentRunFacet parentFacet =
        marquez.service.models.LineageEvent.ParentRunFacet.builder()
            .run(
                marquez.service.models.LineageEvent.RunLink.builder()
                    .runId(parentRunId.toString())
                    .build())
            .job(
                marquez.service.models.LineageEvent.JobLink.builder()
                    .namespace(NAMESPACE)
                    .name(parentJob.getJob().getName())
                    .build())
            .build();

    ImmutableMap<String, Object> childRunFacets =
        ImmutableMap.of("child_facet", ImmutableMap.of("value", "child"));

    UpdateLineageRow childJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "childJob",
            UUID.randomUUID(),
            "COMPLETE",
            jobFacet,
            Arrays.asList(dataset),
            Arrays.asList(),
            parentFacet,
            childRunFacets);

    // Populate denormalized tables for both parent and child
    denormalizedLineageService.populateLineageForRun(parentJob.getRun().getUuid());
    denormalizedLineageService.populateLineageForRun(childJob.getRun().getUuid());

    // Test with aggregateToParentRun=true and facet filtering
    java.util.Set<String> includeFacets = java.util.Set.of("nominalTime");
    Lineage parentLineage =
        lineageService.lineage(
            NodeId.of(new RunId(childJob.getRun().getUuid())),
            2,
            true, // aggregateToParentRun
            includeFacets);

    assertThat(parentLineage.getGraph()).isNotEmpty();

    // The lineage should aggregate to parent run with filtered facets
    // This is the critical test case that prevents PostgreSQL 256MB JSONB limit errors
  }
}

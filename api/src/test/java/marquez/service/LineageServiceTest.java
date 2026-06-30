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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import marquez.db.models.DatasetRow;
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
  public void testRunLineage_edgeBfs_matchesRecursiveCte() {
    // Build a chain A -> d1 -> B -> d2 -> C -> d3 so the middle run B has both an
    // upstream (A) and downstream (C) neighbour at depth 1, and A/C reach further at depth 2.
    UpdateLineageRow a =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "bfs_a",
            "COMPLETE",
            jobFacet,
            List.of(new Dataset(NAMESPACE, "bfs_d0", null)),
            List.of(new Dataset(NAMESPACE, "bfs_d1", null)));
    UpdateLineageRow b =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "bfs_b",
            "COMPLETE",
            jobFacet,
            List.of(new Dataset(NAMESPACE, "bfs_d1", null)),
            List.of(new Dataset(NAMESPACE, "bfs_d2", null)));
    UpdateLineageRow c =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "bfs_c",
            "COMPLETE",
            jobFacet,
            List.of(new Dataset(NAMESPACE, "bfs_d2", null)),
            List.of(new Dataset(NAMESPACE, "bfs_d3", null)));

    // Populate denormalized tables AND lineage_edges for each run.
    for (UUID runUuid : List.of(a.getRun().getUuid(), b.getRun().getUuid(), c.getRun().getUuid())) {
      denormalizedLineageService.populateLineageForRun(runUuid);
    }

    // A second service that reads via the lineage_edges BFS path.
    LineageService bfsService =
        new LineageService(
            lineageDao, jdbi.onDemand(JobDao.class), jdbi.onDemand(RunDao.class), true);

    NodeId middle = NodeId.of(new RunId(b.getRun().getUuid()));
    for (int depth : new int[] {1, 2, 5}) {
      Lineage viaCte = lineageService.lineage(middle, depth, false);
      Lineage viaBfs = bfsService.lineage(middle, depth, false);

      java.util.Set<NodeId> cteNodes =
          viaCte.getGraph().stream().map(Node::getId).collect(Collectors.toSet());
      java.util.Set<NodeId> bfsNodes =
          viaBfs.getGraph().stream().map(Node::getId).collect(Collectors.toSet());

      assertThat(bfsNodes)
          .withFailMessage(
              "lineage_edges BFS must return the same node set as the recursive CTE at depth %d:"
                  + " cte=%s bfs=%s",
              depth, cteNodes, bfsNodes)
          .isEqualTo(cteNodes);
    }
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

  @Test
  public void testLineageV2ForJob() {
    String jobName = "v2LineageJob";
    String inputDatasetName = "v2InputDataset";
    String outputDatasetName = "v2OutputDataset";
    Dataset inputDataset = new Dataset(NAMESPACE, inputDatasetName, newDatasetFacet());
    Dataset outputDataset = new Dataset(NAMESPACE, outputDatasetName, newDatasetFacet());

    UpdateLineageRow run =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            jobName,
            "COMPLETE",
            jobFacet,
            Arrays.asList(inputDataset),
            Arrays.asList(outputDataset));

    denormalizedLineageService.populateDenormalizedEntitiesForEvent(
        run.getNamespace().getUuid(),
        run.getJob().getUuid(),
        Stream.concat(
                run.getInputs().orElse(List.of()).stream(),
                run.getOutputs().orElse(List.of()).stream())
            .map(UpdateLineageRow.DatasetRecord::getDatasetRow)
            .map(DatasetRow::getUuid)
            .distinct()
            .collect(Collectors.toList()));
    denormalizedLineageService.populateLineageForRun(run.getRun().getUuid());

    String persistedJobName = run.getJob().getName();
    String persistedNamespace = run.getJob().getNamespaceName();

    Lineage lineage =
        waitForLineageV2(
            NodeId.of(new NamespaceName(persistedNamespace), new JobName(persistedJobName)), 2, 2);

    assertThat(lineage.getGraph()).isNotEmpty();
    assertThat(lineage.getGraph())
        .anyMatch(
            node ->
                node.getType().equals(NodeType.JOB)
                    && node.getId().asJobId().getName().getValue().equals(persistedJobName));
    assertThat(lineage.getGraph())
        .anyMatch(
            node ->
                node.getType().equals(NodeType.DATASET)
                    && node.getId().asDatasetId().getName().getValue().equals(inputDatasetName));
    assertThat(lineage.getGraph())
        .anyMatch(
            node ->
                node.getType().equals(NodeType.DATASET)
                    && node.getId().asDatasetId().getName().getValue().equals(outputDatasetName));
  }

  @Test
  public void testLineageV2ReturnsEmptyGraphForDatasetWithoutDenormalizedAssociation() {
    DatasetId datasetId =
        new DatasetId(new NamespaceName(NAMESPACE), new DatasetName("v2OrphanDataset"));

    Lineage lineage = lineageService.lineageV2(NodeId.of(datasetId), 2, false);

    assertThat(lineage.getGraph()).isEmpty();
  }

  /**
   * Full integration mock-up of a Spark DAG with a parent run and 3 child task runs.
   *
   * <p>Topology: raw_events ──► [task_user_metrics C1] ──► user_metrics raw_events ──►
   * [task_session_metrics C2] ──► session_metrics raw_events ──► [task_page_metrics C3] ──►
   * page_metrics All 3 tasks are children of the DAG coordinator run P (my_spark_app).
   *
   * <p>With aggregateToParentRun=true the API should collapse C1+C2+C3 into one node P that shows
   * the union of all children's inputs (raw_events once) and outputs (3 distinct datasets).
   */
  @Test
  public void testSparkParentRunAggregatesAllChildLineage() {
    UUID parentRunId = UUID.randomUUID();

    // Parent DAG run has no direct inputs/outputs; tagged with a spark_version facet
    UpdateLineageRow parentRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "my_spark_app",
            parentRunId,
            "COMPLETE",
            jobFacet,
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            ImmutableMap.of("spark_version", ImmutableMap.of("version", "3.3.0")));

    marquez.service.models.LineageEvent.ParentRunFacet parentFacet =
        marquez.service.models.LineageEvent.ParentRunFacet.builder()
            .run(
                marquez.service.models.LineageEvent.RunLink.builder()
                    .runId(parentRunId.toString())
                    .build())
            .job(
                marquez.service.models.LineageEvent.JobLink.builder()
                    .namespace(NAMESPACE)
                    .name("my_spark_app")
                    .build())
            .build();

    // Shared input read by all 3 tasks
    Dataset rawEvents =
        new Dataset(
            NAMESPACE,
            "raw_events",
            newDatasetFacet(
                new SchemaField("event_id", "string", ""), new SchemaField("ts", "long", "")));

    // Each task writes its own output dataset
    Dataset userMetrics =
        new Dataset(
            NAMESPACE, "user_metrics", newDatasetFacet(new SchemaField("user_id", "string", "")));
    Dataset sessionMetrics =
        new Dataset(
            NAMESPACE,
            "session_metrics",
            newDatasetFacet(new SchemaField("session_id", "string", "")));
    Dataset pageMetrics =
        new Dataset(
            NAMESPACE, "page_metrics", newDatasetFacet(new SchemaField("page", "string", "")));

    UpdateLineageRow child1 =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "task_user_metrics",
            UUID.randomUUID(),
            "COMPLETE",
            jobFacet,
            Arrays.asList(rawEvents),
            Arrays.asList(userMetrics),
            parentFacet,
            ImmutableMap.of());
    UpdateLineageRow child2 =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "task_session_metrics",
            UUID.randomUUID(),
            "COMPLETE",
            jobFacet,
            Arrays.asList(rawEvents),
            Arrays.asList(sessionMetrics),
            parentFacet,
            ImmutableMap.of());
    UpdateLineageRow child3 =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "task_page_metrics",
            UUID.randomUUID(),
            "COMPLETE",
            jobFacet,
            Arrays.asList(rawEvents),
            Arrays.asList(pageMetrics),
            parentFacet,
            ImmutableMap.of());

    UUID c1 = child1.getRun().getUuid();
    UUID c2 = child2.getRun().getUuid();
    UUID c3 = child3.getRun().getUuid();

    // Children are already in the DB from createLineageRow; populate denorm for each run.
    // Parent is populated last so populateRunParentLineageDenormalized sees all 3 children.
    denormalizedLineageService.populateLineageForRun(c1);
    denormalizedLineageService.populateLineageForRun(c2);
    denormalizedLineageService.populateLineageForRun(c3);
    denormalizedLineageService.populateLineageForRun(parentRunId);

    // ── Test 1: aggregateToParentRun=true, no facets ──────────────────────────
    Lineage lineage = lineageService.lineage(NodeId.of(new RunId(parentRunId)), 2, true);

    // Graph: 1 RUN node + 1 raw_events version + 3 output versions = 5 nodes total
    assertThat(lineage.getGraph()).hasSize(5);
    assertThat(lineage.getGraph())
        .areExactly(1, new Condition<>(n -> n.getType() == NodeType.RUN, "RUN"))
        .areExactly(
            4, new Condition<>(n -> n.getType() == NodeType.DATASET_VERSION, "DATASET_VERSION"));

    // Find the single RUN node and verify its aggregated content
    Node runNode =
        lineage.getGraph().stream()
            .filter(n -> n.getType() == NodeType.RUN)
            .findFirst()
            .orElseThrow();

    assertThat(runNode.getId()).isEqualTo(NodeId.of(new RunId(parentRunId)));

    marquez.service.models.RunData data = (marquez.service.models.RunData) runNode.getData();
    assertThat(data.getUuid()).isEqualTo(parentRunId);

    // All 3 child task UUIDs collected under the parent
    assertThat(data.getChildRunIds()).hasSize(3).containsExactlyInAnyOrder(c1, c2, c3);

    // parentRunIds on a parent-aggregated node contains P's own UUID (gathered from
    // children's parent_run_uuid column which all point to P). This is different from
    // a child run where parentRunIds correctly points to P as the grandparent.
    assertThat(data.getParentRunIds()).containsExactly(parentRunId);

    // raw_events appears only once even though 3 children read it (DISTINCT in JSON_AGG)
    assertThat(data.getInputDatasetVersions()).hasSize(1);
    assertThat(data.getInputDatasetVersions().get(0).getDatasetVersionId().getName().getValue())
        .isEqualTo("raw_events");

    // All 3 output datasets aggregated
    assertThat(data.getOutputDatasetVersions()).hasSize(3);
    assertThat(data.getOutputDatasetVersions())
        .extracting(dv -> dv.getDatasetVersionId().getName().getValue())
        .containsExactlyInAnyOrder("user_metrics", "session_metrics", "page_metrics");

    // Edges: 1 inEdge (raw_events feeds P), 3 outEdges (P produces 3 datasets)
    assertThat(runNode.getInEdges()).hasSize(1);
    assertThat(runNode.getOutEdges()).hasSize(3);

    // DATASET_VERSION nodes cover all 4 distinct dataset versions
    assertThat(lineage.getGraph())
        .filteredOn(n -> n.getType() == NodeType.DATASET_VERSION)
        .extracting(n -> n.getId().getValue())
        .anySatisfy(id -> assertThat(id).contains("raw_events"))
        .anySatisfy(id -> assertThat(id).contains("user_metrics"))
        .anySatisfy(id -> assertThat(id).contains("session_metrics"))
        .anySatisfy(id -> assertThat(id).contains("page_metrics"));

    // ── Test 2: aggregateToParentRun=true, with includeFacets ────────────────
    Lineage facetLineage =
        lineageService.lineage(
            NodeId.of(new RunId(parentRunId)), 2, true, java.util.Set.of("spark_version"));

    assertThat(facetLineage.getGraph()).hasSize(5);

    Node facetRunNode =
        facetLineage.getGraph().stream()
            .filter(n -> n.getType() == NodeType.RUN)
            .findFirst()
            .orElseThrow();
    marquez.service.models.RunData facetData =
        (marquez.service.models.RunData) facetRunNode.getData();

    // spark_version facet must be present (filter worked — only requested facets are returned)
    assertThat(facetData.getFacets()).isNotNull();
    assertThat(facetData.getFacets().keySet()).containsOnly("spark_version");

    // Aggregation is identical to the no-facets result
    assertThat(facetData.getChildRunIds()).containsExactlyInAnyOrder(c1, c2, c3);
    assertThat(facetData.getInputDatasetVersions()).hasSize(1);
    assertThat(facetData.getOutputDatasetVersions()).hasSize(3);

    // ── Test 3: child run query — aggregateToParentRun=false ─────────────────
    // Each child should appear as its own run with only its own input/output versions.
    Lineage childLineage = lineageService.lineage(NodeId.of(new RunId(c1)), 2, false);

    Node c1Node =
        childLineage.getGraph().stream()
            .filter(n -> n.getType() == NodeType.RUN)
            .findFirst()
            .orElseThrow();
    marquez.service.models.RunData c1Data = (marquez.service.models.RunData) c1Node.getData();

    assertThat(c1Data.getUuid()).isEqualTo(c1);
    // Only its own input (raw_events) and output (user_metrics)
    assertThat(c1Data.getInputDatasetVersions()).hasSize(1);
    assertThat(c1Data.getInputDatasetVersions().get(0).getDatasetVersionId().getName().getValue())
        .isEqualTo("raw_events");
    assertThat(c1Data.getOutputDatasetVersions()).hasSize(1);
    assertThat(c1Data.getOutputDatasetVersions().get(0).getDatasetVersionId().getName().getValue())
        .isEqualTo("user_metrics");
    // parentRunIds carries the parent UUID
    assertThat(c1Data.getParentRunIds()).containsExactly(parentRunId);
  }

  private Lineage waitForLineageV2(NodeId nodeId, int depth, int expectedDatasetCount) {
    AssertionError lastAssertion = null;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

    while (System.nanoTime() < deadline) {
      Lineage lineage = lineageService.lineageV2(nodeId, depth, false);
      try {
        assertThat(lineage.getGraph())
            .filteredOn(node -> node.getType().equals(NodeType.DATASET))
            .hasSizeGreaterThanOrEqualTo(expectedDatasetCount);
        return lineage;
      } catch (AssertionError error) {
        lastAssertion = error;
      }

      try {
        Thread.sleep(50L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(
            "Interrupted while waiting for V2 denormalized lineage", interrupted);
      }
    }

    if (lastAssertion != null) {
      throw lastAssertion;
    }

    return lineageService.lineageV2(nodeId, depth, false);
  }
}

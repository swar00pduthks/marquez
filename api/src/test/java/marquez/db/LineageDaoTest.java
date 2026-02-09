/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db;

import static marquez.db.DatasetDaoTest.DATASET;
import static marquez.db.LineageTestUtils.NAMESPACE;
import static marquez.db.LineageTestUtils.PRODUCER_URL;
import static marquez.db.LineageTestUtils.SCHEMA_URL;
import static marquez.db.LineageTestUtils.newDatasetFacet;
import static marquez.db.LineageTestUtils.writeDownstreamLineage;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Functions;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import marquez.api.JdbiUtils;
import marquez.common.models.JobType;
import marquez.db.LineageDao.UpstreamRunRow;
import marquez.db.LineageTestUtils.DatasetConsumerJob;
import marquez.db.LineageTestUtils.JobLineage;
import marquez.db.models.JobRow;
import marquez.db.models.NamespaceRow;
import marquez.db.models.UpdateLineageRow;
import marquez.jdbi.MarquezJdbiExternalPostgresExtension;
import marquez.service.models.DatasetData;
import marquez.service.models.JobData;
import marquez.service.models.LineageEvent;
import marquez.service.models.LineageEvent.Dataset;
import marquez.service.models.LineageEvent.JobFacet;
import marquez.service.models.LineageEvent.SchemaField;
import marquez.service.models.LineageEvent.SourceCodeLocationJobFacet;
import marquez.service.models.Run;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.ObjectAssert;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.util.PGobject;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
public class LineageDaoTest {

  private static DatasetDao datasetDao;
  private static LineageDao lineageDao;
  private static OpenLineageDao openLineageDao;
  private static marquez.service.DenormalizedLineageService denormalizedLineageService;
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
    LineageDaoTest.jdbi = jdbi;
    datasetDao = jdbi.onDemand(DatasetDao.class);
    lineageDao = jdbi.onDemand(LineageDao.class);
    openLineageDao = jdbi.onDemand(OpenLineageDao.class);
    denormalizedLineageService = new marquez.service.DenormalizedLineageService(jdbi);
  }

  @AfterEach
  public void tearDown(Jdbi jdbi) {
    JdbiUtils.cleanDatabase(jdbi);
  }

  @Test
  public void testGetLineage() {

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
                    new DatasetConsumerJob("downstreamJob", 1, Optional.empty()))),
            jobFacet,
            dataset);

    // don't expect a failed job in the returned lineage
    UpdateLineageRow failedJobRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "readJobFailed",
            "FAILED",
            jobFacet,
            Arrays.asList(dataset),
            Arrays.asList());

    // don't expect a disjoint job in the returned lineage
    UpdateLineageRow disjointJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeRandomDataset",
            "COMPLETE",
            jobFacet,
            Arrays.asList(
                new Dataset(
                    NAMESPACE,
                    "randomDataset",
                    newDatasetFacet(
                        new SchemaField("firstname", "string", "the first name"),
                        new SchemaField("lastname", "string", "the last name")))),
            Arrays.asList());
    // fetch the first "readJob" lineage.
    Set<JobData> connectedJobs =
        lineageDao.getLineage(new HashSet<>(Arrays.asList(jobRows.get(0).getId())), 2);

    // 20 readJobs + 1 downstreamJob for each (20) + 1 write job = 41
    assertThat(connectedJobs).size().isEqualTo(41);

    Set<UUID> jobIds = connectedJobs.stream().map(JobData::getUuid).collect(Collectors.toSet());
    // expect the job that wrote "commonDataset", which is readJob0's input
    assertThat(jobIds).contains(writeJob.getJob().getUuid());

    // expect all downstream jobs
    Set<UUID> readJobUUIDs =
        jobRows.stream()
            .flatMap(row -> Stream.concat(Stream.of(row), row.getDownstreamJobs().stream()))
            .map(JobLineage::getId)
            .collect(Collectors.toSet());
    assertThat(jobIds).containsAll(readJobUUIDs);

    // expect that the failed job that reads the same input dataset is not present
    assertThat(jobIds).doesNotContain(failedJobRow.getJob().getUuid());

    // expect that the disjoint job that reads a random dataset is not present
    assertThat(jobIds).doesNotContain(disjointJob.getJob().getUuid());

    Map<UUID, JobData> actualJobRows =
        connectedJobs.stream().collect(Collectors.toMap(JobData::getUuid, Functions.identity()));
    for (JobLineage expected : jobRows) {
      JobData job = actualJobRows.get(expected.getId());
      assertThat(job.getInputUuids())
          .containsAll(
              expected.getInput().map(ds -> ds.getDatasetRow().getUuid()).stream()::iterator);
      assertThat(job.getOutputUuids())
          .containsAll(
              expected.getOutput().map(ds -> ds.getDatasetRow().getUuid()).stream()::iterator);
    }
  }

  @Test
  public void testGetLineageForSymlinkedJob() throws SQLException {

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
                    new DatasetConsumerJob("downstreamJob", 1, Optional.empty()))),
            jobFacet,
            dataset);

    NamespaceRow namespaceRow =
        jdbi.onDemand(NamespaceDao.class)
            .findNamespaceByName(writeJob.getJob().getNamespaceName())
            .get();

    PGobject inputs = new PGobject();
    inputs.setType("json");
    inputs.setValue("[]");

    String symlinkTargetJobName = "A_new_write_job";
    JobRow targetJob =
        jdbi.onDemand(JobDao.class)
            .upsertJob(
                UUID.randomUUID(),
                JobType.valueOf(writeJob.getJob().getType()),
                Instant.now(),
                namespaceRow.getUuid(),
                writeJob.getJob().getNamespaceName(),
                symlinkTargetJobName,
                writeJob.getJob().getDescription().orElse(null),
                writeJob.getJob().getLocation(),
                null,
                inputs);
    jdbi.onDemand(JobDao.class)
        .upsertJob(
            writeJob.getJob().getUuid(),
            JobType.valueOf(writeJob.getJob().getType()),
            Instant.now(),
            namespaceRow.getUuid(),
            writeJob.getJob().getNamespaceName(),
            writeJob.getJob().getName(),
            writeJob.getJob().getDescription().orElse(null),
            writeJob.getJob().getLocation(),
            targetJob.getUuid(),
            inputs);

    // fetch the first "targetJob" lineage.
    Set<JobData> connectedJobs =
        lineageDao.getLineage(new HashSet<>(Arrays.asList(targetJob.getUuid())), 2);

    // 20 readJobs + 1 downstreamJob for each (20) + 1 write job = 41
    assertThat(connectedJobs).size().isEqualTo(41);

    Set<UUID> jobIds = connectedJobs.stream().map(JobData::getUuid).collect(Collectors.toSet());
    // expect the job that wrote "commonDataset", which is readJob0's input
    assertThat(jobIds).contains(targetJob.getUuid());

    // expect all downstream jobs
    Set<UUID> readJobUUIDs =
        jobRows.stream()
            .flatMap(row -> Stream.concat(Stream.of(row), row.getDownstreamJobs().stream()))
            .map(JobLineage::getId)
            .collect(Collectors.toSet());
    assertThat(jobIds).containsAll(readJobUUIDs);

    Map<UUID, JobData> actualJobRows =
        connectedJobs.stream().collect(Collectors.toMap(JobData::getUuid, Functions.identity()));
    for (JobLineage expected : jobRows) {
      JobData job = actualJobRows.get(expected.getId());
      assertThat(job.getInputUuids())
          .containsAll(
              expected.getInput().map(ds -> ds.getDatasetRow().getUuid()).stream()::iterator);
      assertThat(job.getOutputUuids())
          .containsAll(
              expected.getOutput().map(ds -> ds.getDatasetRow().getUuid()).stream()::iterator);
    }
    Set<UUID> lineageForOriginalJob =
        lineageDao.getLineage(new HashSet<>(Arrays.asList(writeJob.getJob().getUuid())), 2).stream()
            .map(JobData::getUuid)
            .collect(Collectors.toSet());
    assertThat(lineageForOriginalJob).isEqualTo(jobIds);

    UpdateLineageRow updatedTargetJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            symlinkTargetJobName,
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(
                new Dataset(
                    NAMESPACE,
                    "a_new_dataset",
                    newDatasetFacet(new SchemaField("firstname", "string", "the first name")))));
    assertThat(updatedTargetJob.getJob().getUuid()).isEqualTo(targetJob.getUuid());

    // get lineage for original job - the old datasets/jobs should no longer be present
    assertThat(
            lineageDao
                .getLineage(new HashSet<>(Arrays.asList(writeJob.getJob().getUuid())), 2)
                .stream()
                .map(JobData::getUuid)
                .collect(Collectors.toSet()))
        .hasSize(1)
        .containsExactlyInAnyOrder(targetJob.getUuid());

    // fetching lineage for target job should yield the same results
    assertThat(
            lineageDao.getLineage(new HashSet<>(Arrays.asList(targetJob.getUuid())), 2).stream()
                .map(JobData::getUuid)
                .collect(Collectors.toSet()))
        .hasSize(1)
        .containsExactlyInAnyOrder(targetJob.getUuid());
  }

  @Test
  public void testGetLineageWithJobThatHasNoDownstreamConsumers() {

    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));
    Set<UUID> lineage =
        lineageDao.getLineage(Collections.singleton(writeJob.getJob().getUuid()), 2).stream()
            .map(JobData::getUuid)
            .collect(Collectors.toSet());
    assertThat(lineage).hasSize(1).contains(writeJob.getJob().getUuid());
  }

  @Test
  public void testGetLineageWithJobThatHasNoDatasets() {

    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao, "writeJob", "COMPLETE", jobFacet, Arrays.asList(), Arrays.asList());
    Set<UUID> lineage =
        lineageDao.getLineage(Collections.singleton(writeJob.getJob().getUuid()), 2).stream()
            .map(JobData::getUuid)
            .collect(Collectors.toSet());

    assertThat(lineage).hasSize(1).first().isEqualTo(writeJob.getJob().getUuid());
  }

  @Test
  public void testGetLineageWithNewJobInRunningState() {

    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "RUNNING",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));
    Set<JobData> lineage =
        lineageDao.getLineage(Collections.singleton(writeJob.getJob().getUuid()), 2);

    // assert the job does exist
    ObjectAssert<JobData> writeAssert = assertThat(lineage).hasSize(1).first();
    writeAssert.extracting(JobData::getUuid).isEqualTo(writeJob.getJob().getUuid());

    // job in running state doesn't yet have any datasets in its lineage
    writeAssert
        .extracting(JobData::getOutputUuids, InstanceOfAssertFactories.iterable(UUID.class))
        .isEmpty();
    writeAssert
        .extracting(JobData::getInputUuids, InstanceOfAssertFactories.iterable(UUID.class))
        .isEmpty();
  }

  /**
   * Validate a job that consumes a dataset, but shares no datasets with any other job returns only
   * the consumed dataset
   */
  @Test
  public void testGetLineageWithJobThatSharesNoDatasets() {
    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(dataset),
            Arrays.asList());

    // write a new dataset with a different name
    Dataset anotherDataset =
        new Dataset(
            NAMESPACE,
            "anUncommonDataset",
            newDatasetFacet(
                new SchemaField("firstname", "string", "the first name"),
                new SchemaField("lastname", "string", "the last name"),
                new SchemaField("birthdate", "date", "the date of birth")));
    // write a bunch of jobs that share nothing with the writeJob
    writeDownstreamLineage(
        openLineageDao,
        Arrays.asList(new DatasetConsumerJob("consumer", 5, Optional.empty())),
        jobFacet,
        anotherDataset);

    // Validate that finalConsumer job only has a single dataset
    Set<UUID> jobIds = Collections.singleton(writeJob.getJob().getUuid());
    Set<JobData> finalConsumer = lineageDao.getLineage(jobIds, 2);
    assertThat(finalConsumer).hasSize(1).flatMap(JobData::getUuid).hasSize(1).containsAll(jobIds);
  }

  /** A failed consumer job doesn't show up in the datasets out edges */
  @Test
  public void testGetLineageWithFailedConsumer() {
    JobFacet jobFacet = JobFacet.builder().build();

    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));
    LineageTestUtils.createLineageRow(
        openLineageDao,
        "failedConsumer",
        "FAILED",
        jobFacet,
        Arrays.asList(dataset),
        Arrays.asList());
    Set<JobData> lineage =
        lineageDao.getLineage(Collections.singleton(writeJob.getJob().getUuid()), 2);

    assertThat(lineage)
        .hasSize(1)
        .extracting(JobData::getUuid)
        .contains(writeJob.getJob().getUuid());
  }

  /**
   * Test that a job with multiple versions will only return the datasets touched by the latest
   * version.
   */
  @Test
  public void testGetInputDatasetsWithJobThatHasMultipleVersions() {

    UpdateLineageRow writeJob =
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
                new DatasetConsumerJob("readJob", 3, Optional.of("outputData")),
                new DatasetConsumerJob("downstreamJob", 1, Optional.empty()))),
        jobFacet,
        dataset);

    JobFacet newVersionFacet =
        JobFacet.builder()
            .sourceCodeLocation(
                SourceCodeLocationJobFacet.builder().url("git@github:location").build())
            .additional(LineageTestUtils.EMPTY_MAP)
            .build();

    // readJobV2 produces outputData2 and not outputData
    List<JobLineage> newRows =
        writeDownstreamLineage(
            openLineageDao,
            new LinkedList<>(
                Arrays.asList(
                    new DatasetConsumerJob("readJob", 3, Optional.of("outputData2")),
                    new DatasetConsumerJob("downstreamJob", 1, Optional.empty()))),
            newVersionFacet,
            dataset);

    Set<JobData> lineage =
        lineageDao.getLineage(
            new HashSet<>(
                Arrays.asList(
                    newRows.get(0).getId(), newRows.get(0).getDownstreamJobs().get(0).getId())),
            2);
    assertThat(lineage)
        .hasSize(7)
        .extracting(JobData::getUuid)
        .containsAll(
            newRows.stream()
                    .flatMap(r -> Stream.concat(Stream.of(r), r.getDownstreamJobs().stream()))
                    .map(JobLineage::getId)
                ::iterator);
    assertThat(lineage)
        .filteredOn(r -> r.getName().getValue().equals("readJob0<-commonDataset"))
        .hasSize(1)
        .first()
        .extracting(JobData::getOutputUuids, InstanceOfAssertFactories.iterable(UUID.class))
        .hasSize(1)
        .first()
        .isEqualTo(newRows.get(0).getOutput().get().getDatasetRow().getUuid());

    assertThat(lineage)
        .filteredOn(
            r ->
                r.getName()
                    .getValue()
                    .equals("downstreamJob0<-outputData2<-readJob0<-commonDataset"))
        .hasSize(1)
        .first()
        .extracting(JobData::getInputUuids, InstanceOfAssertFactories.iterable(UUID.class))
        .hasSize(1)
        .first()
        .isEqualTo(
            newRows.get(0).getDownstreamJobs().get(0).getInput().get().getDatasetRow().getUuid());
    assertThat(lineage)
        .filteredOn(
            r ->
                r.getName()
                    .getValue()
                    .equals("downstreamJob0<-outputData2<-readJob0<-commonDataset"))
        .hasSize(1)
        .first()
        .extracting(JobData::getOutputUuids, InstanceOfAssertFactories.iterable(UUID.class))
        .isEmpty();
  }

  /** A failed producer job doesn't show up in the lineage */
  @Test
  public void testGetLineageWithFailedProducer() {
    JobFacet jobFacet = JobFacet.builder().build();

    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));
    LineageTestUtils.createLineageRow(
        openLineageDao,
        "failedProducer",
        "FAILED",
        jobFacet,
        Arrays.asList(),
        Arrays.asList(dataset));
    Set<JobData> inputDatasets =
        lineageDao.getLineage(Collections.singleton(writeJob.getJob().getUuid()), 2);
    assertThat(inputDatasets)
        .hasSize(1)
        .flatMap(JobData::getUuid)
        .hasSize(1)
        .contains(writeJob.getJob().getUuid());
  }

  /** A failed producer job doesn't show up in the lineage */
  @Test
  public void testGetLineageChangedJobVersion() {
    JobFacet jobFacet = JobFacet.builder().build();

    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));
    LineageTestUtils.createLineageRow(
        openLineageDao, "writeJob", "COMPLETE", jobFacet, Arrays.asList(), Arrays.asList());

    // the new job is still returned, even though it isn't connected
    Set<JobData> jobData =
        lineageDao.getLineage(Collections.singleton(writeJob.getJob().getUuid()), 2);
    assertThat(jobData)
        .hasSize(1)
        .first()
        .matches(jd -> jd.getUuid().equals(writeJob.getJob().getUuid()))
        .extracting(JobData::getOutputUuids, InstanceOfAssertFactories.iterable(UUID.class))
        .isEmpty();
  }

  @Test
  public void testGetJobFromInputOrOutput() {
    JobFacet jobFacet = JobFacet.builder().build();

    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));
    LineageTestUtils.createLineageRow(
        openLineageDao,
        "consumerJob",
        "COMPLETE",
        jobFacet,
        Arrays.asList(dataset),
        Arrays.asList());
    Optional<UUID> jobNode =
        lineageDao.getJobFromInputOrOutput(dataset.getName(), dataset.getNamespace());
    assertThat(jobNode).isPresent().get().isEqualTo(writeJob.getJob().getUuid());
  }

  @Test
  public void testGetJobFromInputOrOutputPrefersRecentOutputJob() {
    JobFacet jobFacet = JobFacet.builder().build();

    // add some consumer jobs prior to the write so we know that the sort isn't simply picking
    // the first job created
    for (int i = 0; i < 5; i++) {
      LineageTestUtils.createLineageRow(
          openLineageDao,
          "consumerJob" + i,
          "COMPLETE",
          jobFacet,
          Arrays.asList(dataset),
          Arrays.asList());
    }
    // older write job- should be ignored.
    LineageTestUtils.createLineageRow(
        openLineageDao,
        "olderWriteJob",
        "COMPLETE",
        jobFacet,
        Arrays.asList(),
        Arrays.asList(dataset));

    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));
    LineageTestUtils.createLineageRow(
        openLineageDao,
        "consumerJob",
        "COMPLETE",
        jobFacet,
        Arrays.asList(dataset),
        Arrays.asList());
    Optional<UUID> jobNode =
        lineageDao.getJobFromInputOrOutput(dataset.getName(), dataset.getNamespace());
    assertThat(jobNode).isPresent().get().isEqualTo(writeJob.getJob().getUuid());
  }

  @Test
  public void testGetDatasetData() {
    LineageTestUtils.createLineageRow(
        openLineageDao, "writeJob", "COMPLETE", jobFacet, Arrays.asList(), Arrays.asList(dataset));
    List<JobLineage> newRows =
        writeDownstreamLineage(
            openLineageDao,
            new LinkedList<>(
                Arrays.asList(
                    new DatasetConsumerJob("readJob", 3, Optional.of("outputData2")),
                    new DatasetConsumerJob("downstreamJob", 1, Optional.empty()))),
            jobFacet,
            dataset);
    Set<DatasetData> datasetData =
        lineageDao.getDatasetData(
            newRows.stream()
                .map(j -> j.getOutput().get().getDatasetRow().getUuid())
                .collect(Collectors.toSet()));
    assertThat(datasetData)
        .hasSize(3)
        .extracting(ds -> ds.getName().getValue())
        .allMatch(str -> str.contains("outputData2"));
  }

  @Test
  public void testGetDatasetDatalifecycleStateReturned() {
    Dataset dataset =
        new Dataset(
            NAMESPACE,
            DATASET,
            LineageEvent.DatasetFacets.builder()
                .lifecycleStateChange(
                    new LineageEvent.LifecycleStateChangeFacet(PRODUCER_URL, SCHEMA_URL, "CREATE"))
                .build());

    UpdateLineageRow row =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));

    Set<DatasetData> datasetData =
        lineageDao.getDatasetData(
            Collections.singleton(row.getOutputs().get().get(0).getDatasetRow().getUuid()));

    assertThat(datasetData)
        .extracting(ds -> ds.getLastLifecycleState().orElse(""))
        .anyMatch(str -> str.contains("CREATE"));
  }

  @Test
  public void testGetDatasetDataDoesNotReturnDeletedDataset() {
    Dataset dataset =
        new Dataset(
            NAMESPACE,
            DATASET,
            LineageEvent.DatasetFacets.builder()
                .lifecycleStateChange(
                    new LineageEvent.LifecycleStateChangeFacet(PRODUCER_URL, SCHEMA_URL, "CREATE"))
                .build());

    String deleteName = DATASET + "-delete";
    Dataset toDelete =
        new Dataset(
            NAMESPACE,
            deleteName,
            LineageEvent.DatasetFacets.builder()
                .lifecycleStateChange(
                    new LineageEvent.LifecycleStateChangeFacet(PRODUCER_URL, SCHEMA_URL, "CREATE"))
                .build());

    UpdateLineageRow row =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset, toDelete));

    Set<DatasetData> datasetData =
        lineageDao.getDatasetData(
            Set.of(
                row.getOutputs().get().get(0).getDatasetRow().getUuid(),
                row.getOutputs().get().get(1).getDatasetRow().getUuid()));

    assertThat(datasetData)
        .hasSize(2)
        .extracting(ds -> ds.getName().getValue())
        .anyMatch(str -> str.contains(deleteName));

    datasetDao.delete(NAMESPACE, deleteName);

    datasetData =
        lineageDao.getDatasetData(
            Set.of(
                row.getOutputs().get().get(0).getDatasetRow().getUuid(),
                row.getOutputs().get().get(1).getDatasetRow().getUuid()));

    assertThat(datasetData)
        .hasSize(1)
        .extracting(ds -> ds.getName().getValue())
        .allMatch(str -> str.contains(DATASET));
  }

  @Test
  public void testGetCurrentRuns() {
    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset));
    List<JobLineage> newRows =
        writeDownstreamLineage(
            openLineageDao,
            new LinkedList<>(
                Arrays.asList(
                    new DatasetConsumerJob("readJob", 3, Optional.of("outputData2")),
                    new DatasetConsumerJob("downstreamJob", 1, Optional.empty()))),
            jobFacet,
            dataset);

    Set<UUID> expectedRunIds =
        Stream.concat(
                Stream.of(writeJob.getRun().getUuid()), newRows.stream().map(JobLineage::getRunId))
            .collect(Collectors.toSet());
    Set<UUID> jobids =
        Stream.concat(
                Stream.of(writeJob.getJob().getUuid()), newRows.stream().map(JobLineage::getId))
            .collect(Collectors.toSet());

    List<Run> currentRuns = lineageDao.getCurrentRuns(jobids);

    // assert the job does exist
    assertThat(currentRuns)
        .hasSize(expectedRunIds.size())
        .extracting(r -> r.getId().getValue())
        .containsAll(expectedRunIds);
  }

  @Test
  public void testGetCurrentRunsWithFailedJob() {
    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao, "writeJob", "FAIL", jobFacet, Arrays.asList(), Arrays.asList(dataset));

    Set<UUID> jobids = Collections.singleton(writeJob.getJob().getUuid());

    List<Run> currentRuns = lineageDao.getCurrentRuns(jobids);

    // assert the job does exist
    assertThat(currentRuns)
        .hasSize(1)
        .extracting(r -> r.getId().getValue())
        .contains(writeJob.getRun().getUuid());
  }

  @Test
  public void testGetCurrentRunsWithFacetsGetsLatestRun() {
    for (int i = 0; i < 5; i++) {
      LineageTestUtils.createLineageRow(
          openLineageDao,
          "writeJob",
          "COMPLETE",
          jobFacet,
          Arrays.asList(),
          Arrays.asList(dataset));
    }

    List<JobLineage> newRows =
        writeDownstreamLineage(
            openLineageDao,
            new LinkedList<>(
                Arrays.asList(
                    new DatasetConsumerJob("readJob", 3, Optional.of("outputData2")),
                    new DatasetConsumerJob("downstreamJob", 1, Optional.empty()))),
            jobFacet,
            dataset);
    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao, "writeJob", "FAIL", jobFacet, Arrays.asList(), Arrays.asList(dataset));

    Set<UUID> expectedRunIds =
        Stream.concat(
                Stream.of(writeJob.getRun().getUuid()), newRows.stream().map(JobLineage::getRunId))
            .collect(Collectors.toSet());
    Set<UUID> jobids =
        Stream.concat(
                Stream.of(writeJob.getJob().getUuid()), newRows.stream().map(JobLineage::getId))
            .collect(Collectors.toSet());

    List<Run> currentRuns = lineageDao.getCurrentRunsWithFacets(jobids);

    // assert the job does exist
    assertThat(currentRuns)
        .hasSize(expectedRunIds.size())
        .extracting(r -> r.getId().getValue())
        .containsAll(expectedRunIds);

    // assert that run_args, input/output versions, and run facets are fetched from the dao.
    for (Run run : currentRuns) {
      assertThat(run.getArgs()).hasSize(2);
      assertThat(run.getOutputDatasetVersions()).hasSize(1);
      assertThat(run.getFacets()).hasSize(1);
    }
  }

  @Test
  public void testGetCurrentRunsGetsLatestRun() {
    for (int i = 0; i < 5; i++) {
      LineageTestUtils.createLineageRow(
          openLineageDao,
          "writeJob",
          "COMPLETE",
          jobFacet,
          Arrays.asList(),
          Arrays.asList(dataset));
    }

    List<JobLineage> newRows =
        writeDownstreamLineage(
            openLineageDao,
            new LinkedList<>(
                Arrays.asList(
                    new DatasetConsumerJob("readJob", 3, Optional.of("outputData2")),
                    new DatasetConsumerJob("downstreamJob", 1, Optional.empty()))),
            jobFacet,
            dataset);
    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao, "writeJob", "FAIL", jobFacet, Arrays.asList(), Arrays.asList(dataset));

    Set<UUID> expectedRunIds =
        Stream.concat(
                Stream.of(writeJob.getRun().getUuid()), newRows.stream().map(JobLineage::getRunId))
            .collect(Collectors.toSet());
    Set<UUID> jobids =
        Stream.concat(
                Stream.of(writeJob.getJob().getUuid()), newRows.stream().map(JobLineage::getId))
            .collect(Collectors.toSet());

    List<Run> currentRuns = lineageDao.getCurrentRuns(jobids);

    // assert the job does exist
    assertThat(currentRuns)
        .hasSize(expectedRunIds.size())
        .extracting(r -> r.getId().getValue())
        .containsAll(expectedRunIds);
  }

  @Test
  public void testGetRunLineage() {

    Dataset upstreamDataset = new Dataset(NAMESPACE, "upstreamDataset", null);

    UpdateLineageRow upstreamJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "upstreamJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(upstreamDataset));

    UpdateLineageRow writeJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeJob",
            "COMPLETE",
            jobFacet,
            Arrays.asList(upstreamDataset),
            Arrays.asList(dataset));
    List<JobLineage> jobRows =
        writeDownstreamLineage(
            openLineageDao,
            new LinkedList<>(
                Arrays.asList(
                    new DatasetConsumerJob("readJob", 20, Optional.of("outputData")),
                    new DatasetConsumerJob("downstreamJob", 1, Optional.empty()))),
            jobFacet,
            dataset);

    // don't expect a failed job in the returned lineage
    UpdateLineageRow failedJobRow =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "readJobFailed",
            "FAILED",
            jobFacet,
            Arrays.asList(dataset),
            Arrays.asList());

    // don't expect a disjoint job in the returned lineage
    UpdateLineageRow disjointJob =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "writeRandomDataset",
            "COMPLETE",
            jobFacet,
            Arrays.asList(
                new Dataset(
                    NAMESPACE,
                    "randomDataset",
                    newDatasetFacet(
                        new SchemaField("firstname", "string", "the first name"),
                        new SchemaField("lastname", "string", "the last name")))),
            Arrays.asList());

    {
      List<UpstreamRunRow> upstream =
          lineageDao.getUpstreamRuns(failedJobRow.getRun().getUuid(), 10);

      assertThat(upstream).size().isEqualTo(3);
      assertThat(upstream.get(0).job().name().getValue())
          .isEqualTo(failedJobRow.getJob().getName());
      assertThat(upstream.get(0).input().name().getValue()).isEqualTo(dataset.getName());
      assertThat(upstream.get(1).job().name().getValue()).isEqualTo(writeJob.getJob().getName());
      assertThat(upstream.get(1).input().name().getValue()).isEqualTo(upstreamDataset.getName());
      assertThat(upstream.get(2).job().name().getValue()).isEqualTo(upstreamJob.getJob().getName());
    }

    {
      List<UpstreamRunRow> upstream2 = lineageDao.getUpstreamRuns(jobRows.get(0).getRunId(), 10);

      assertThat(upstream2).size().isEqualTo(3);
      assertThat(upstream2.get(0).job().name().getValue()).isEqualTo(jobRows.get(0).getName());
      assertThat(upstream2.get(0).input().name().getValue()).isEqualTo(dataset.getName());
      assertThat(upstream2.get(1).job().name().getValue()).isEqualTo(writeJob.getJob().getName());
      assertThat(upstream2.get(1).input().name().getValue()).isEqualTo(upstreamDataset.getName());
      assertThat(upstream2.get(2).job().name().getValue())
          .isEqualTo(upstreamJob.getJob().getName());
    }
  }

  @Test
  public void testGetRunLineageWithIncludeFacets() {
    // Create custom run facets for testing
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

    // Create a job with multiple facets
    UpdateLineageRow jobWithFacets =
        LineageTestUtils.createLineageRow(
            openLineageDao,
            "jobWithFacets",
            UUID.randomUUID(),
            "COMPLETE",
            jobFacet,
            Arrays.asList(),
            Arrays.asList(dataset),
            null,
            runFacets);

    // Populate denormalized tables
    denormalizedLineageService.populateLineageForRun(jobWithFacets.getRun().getUuid());

    Set<UUID> runIds = Set.of(jobWithFacets.getRun().getUuid());

    // Test 1: Original method should NOT return facets
    marquez.service.models.RunData noFacetsData =
        lineageDao.getRunLineage(runIds, 10, null, null).stream().findFirst().orElse(null);
    assertThat(noFacetsData).isNotNull();
    // Original method doesn't include facets at all

    // Test 2: Filter to include only specific facets
    Set<String> includeFacets = Set.of("spark");
    marquez.service.models.RunData filteredData =
        lineageDao.getRunLineageWithFacets(runIds, 10, includeFacets, null, null).stream()
            .findFirst()
            .orElse(null);
    assertThat(filteredData).isNotNull();
    assertThat(filteredData.getFacets()).isNotNull();
    assertThat(filteredData.getFacets()).hasSize(1);
    assertThat(filteredData.getFacets().keySet()).containsExactly("spark");

    // Test 3: Filter to include multiple facets
    Set<String> multipleIncludeFacets = Set.of("spark", "processing_engine");
    marquez.service.models.RunData multiFilteredData =
        lineageDao.getRunLineageWithFacets(runIds, 10, multipleIncludeFacets, null, null).stream()
            .findFirst()
            .orElse(null);
    assertThat(multiFilteredData).isNotNull();
    assertThat(multiFilteredData.getFacets()).isNotNull();
    assertThat(multiFilteredData.getFacets()).hasSize(2);
    assertThat(multiFilteredData.getFacets().keySet())
        .containsExactlyInAnyOrder("spark", "processing_engine");
  }

  @Test
  public void testGetParentRunLineageWithIncludeFacets() {
    // Create parent run with facets
    ImmutableMap<String, Object> parentSparkFacet =
        ImmutableMap.of(
            "spark_version",
            "3.2.0",
            "spark.properties",
            ImmutableMap.of("spark.executor.cores", "4"));

    ImmutableMap<String, Object> parentRunFacets =
        ImmutableMap.of(
            "spark", parentSparkFacet, "processing_engine", ImmutableMap.of("version", "3.0.0"));

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

    // Create child run with parent facet
    LineageEvent.ParentRunFacet parentFacet =
        LineageEvent.ParentRunFacet.builder()
            .run(LineageEvent.RunLink.builder().runId(parentRunId.toString()).build())
            .job(
                LineageEvent.JobLink.builder()
                    .namespace(NAMESPACE)
                    .name(parentJob.getJob().getName())
                    .build())
            .build();

    ImmutableMap<String, Object> childRunFacets =
        ImmutableMap.of("child_facet", ImmutableMap.of("value", "test"));

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

    // Populate denormalized tables
    denormalizedLineageService.populateLineageForRun(parentJob.getRun().getUuid());
    denormalizedLineageService.populateLineageForRun(childJob.getRun().getUuid());

    // Query with parent run UUID since parent lineage aggregates to parent
    // For parent aggregation, query with parent run UUID
    Set<UUID> runIds = Set.of(parentJob.getRun().getUuid());

    // Test 1: Original method should NOT return facets
    // Parent lineage returns aggregated data with parent run UUID
    marquez.service.models.RunData noFacetsData =
        lineageDao.getParentRunLineage(runIds, 10, null, null).stream()
            .filter(rd -> rd.getUuid().equals(parentJob.getRun().getUuid()))
            .findFirst()
            .orElse(null);
    assertThat(noFacetsData).isNotNull();
    // Original method doesn't include facets column - should be null or empty
    assertThat(noFacetsData.getFacets())
        .satisfiesAnyOf(
            facets -> assertThat(facets).isNull(), facets -> assertThat(facets).isEmpty());

    // Test 2: Filter to specific facets
    // Request parent's "spark" facet and child's "child_facet"
    Set<String> includeFacets = Set.of("spark", "child_facet");
    marquez.service.models.RunData filteredData =
        lineageDao.getParentRunLineageWithFacets(runIds, 10, includeFacets, null, null).stream()
            .filter(rd -> rd.getUuid().equals(parentJob.getRun().getUuid()))
            .findFirst()
            .orElse(null);
    assertThat(filteredData).isNotNull();
    assertThat(filteredData.getFacets()).isNotNull();
    // Should have both spark and child_facet from aggregated runs
    assertThat(filteredData.getFacets().keySet()).contains("spark", "child_facet");
    assertThat(filteredData.getFacets().keySet()).doesNotContain("processing_engine");
  }

  @Test
  public void testFacetFilteringPreventsLargeJsonAggregation() {
    // This test verifies that facet filtering happens at SQL level
    // by ensuring that even with many runs and large facets,
    // filtering to a small set of facets keeps the response small

    // Create a large facet that would normally cause issues
    ImmutableMap.Builder<String, Object> largeFacetBuilder = ImmutableMap.builder();
    for (int i = 0; i < 100; i++) {
      largeFacetBuilder.put("large_property_" + i, "value_" + i);
    }

    ImmutableMap<String, Object> largeFacet =
        ImmutableMap.of("spark.properties", largeFacetBuilder.build());

    // Create multiple jobs with large facets
    List<UUID> runIds = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      UpdateLineageRow job =
          LineageTestUtils.createLineageRow(
              openLineageDao,
              "largeJob" + i,
              UUID.randomUUID(),
              "COMPLETE",
              jobFacet,
              i == 0 ? Arrays.asList() : Arrays.asList(dataset),
              Arrays.asList(dataset),
              null,
              largeFacet);
      runIds.add(job.getRun().getUuid());

      // Populate denormalized tables
      denormalizedLineageService.populateLineageForRun(job.getRun().getUuid());
    }

    // Query with facet filtering - should only return nominalTime facet
    Set<String> includeFacets = Set.of("nominalTime");
    Set<marquez.service.models.RunData> filteredResults =
        lineageDao.getRunLineageWithFacets(Set.copyOf(runIds), 10, includeFacets, null, null);

    assertThat(filteredResults).isNotEmpty();

    // Verify each result only has the nominalTime facet, not the large spark.properties
    for (marquez.service.models.RunData runData : filteredResults) {
      assertThat(runData.getFacets()).isNotNull();
      if (!runData.getFacets().isEmpty()) {
        assertThat(runData.getFacets().keySet()).containsOnly("nominalTime");
        assertThat(runData.getFacets().keySet()).doesNotContain("spark");
      }
    }
  }
}

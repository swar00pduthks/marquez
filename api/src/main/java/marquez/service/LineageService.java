/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.common.models.DatasetId;
import marquez.common.models.DatasetVersionId;
import marquez.common.models.InputDatasetVersion;
import marquez.common.models.JobId;
import marquez.common.models.OutputDatasetVersion;
import marquez.common.models.RunId;
import marquez.db.JobDao;
import marquez.db.LineageDao;
import marquez.db.LineageDao.DatasetSummary;
import marquez.db.LineageDao.JobSummary;
import marquez.db.LineageDao.RunSummary;
import marquez.db.RunDao;
import marquez.db.models.JobRow;
import marquez.service.DelegatingDaos.DelegatingLineageDao;
import marquez.service.LineageService.UpstreamRunLineage;
import marquez.service.exceptions.NodeIdNotFoundException;
import marquez.service.models.DatasetData;
import marquez.service.models.DatasetVersionData;
import marquez.service.models.Edge;
import marquez.service.models.Graph;
import marquez.service.models.JobData;
import marquez.service.models.Lineage;
import marquez.service.models.Node;
import marquez.service.models.NodeId;
import marquez.service.models.NodeType;
import marquez.service.models.Run;
import marquez.service.models.RunData;

@Slf4j
public class LineageService extends DelegatingLineageDao {

  public record UpstreamRunLineage(List<UpstreamRun> runs) {}

  public record UpstreamRun(JobSummary job, RunSummary run, List<DatasetSummary> inputs) {}

  private final JobDao jobDao;

  private final RunDao runDao;

  public LineageService(LineageDao delegate, JobDao jobDao, RunDao runDao) {
    super(delegate);
    this.jobDao = jobDao;
    this.runDao = runDao;
  }

  // TODO make input parameters easily extendable if adding more options like 'withJobFacets'
  public Lineage lineage(NodeId nodeId, int depth, boolean aggregateToParentRun) {
    log.debug("Attempting to get lineage for node '{}' with depth '{}'", nodeId.getValue(), depth);

    if (nodeId.isRunType() || nodeId.isDatasetVersionType()) {
      Set<UUID> runIds =
          nodeId.isRunType()
              ? Set.of(nodeId.asRunId().getValue())
              : aggregateToParentRun
                  ? runDao.findParentRunFromDatasetVersionUuids(
                      nodeId.asDatasetVersionId().getVersion())
                  : runDao.findRunFromDatasetVersionUuids(nodeId.asDatasetVersionId().getVersion());
      log.debug("Attempting to get lineage for run '{}'", runIds);

      boolean hasChildren = this.hasChildRuns(runIds);
      log.debug("Run '{}' has children: {}", runIds, hasChildren);
      Set<RunData> runData;

      if (hasChildren && aggregateToParentRun) {
        runData = getParentRunLineage(runIds, depth);
      } else {
        runData = getRunLineage(runIds, depth);
      }
      System.out.println("runData: " + runData);
      log.debug("Retrieved run data for '{}': {}", runIds, runData);

      if (runData.isEmpty()) {
        log.warn("Failed to get lineage for run '{}', returning empty graph...", nodeId.getValue());
        return new Lineage(ImmutableSortedSet.of());
      }

      return toRunLineage(runData);
    } else {

      Optional<UUID> optionalUUID = getJobUuid(nodeId);
      if (optionalUUID.isEmpty()) {
        log.warn(
            "Failed to get job associated with node '{}', returning orphan graph...",
            nodeId.getValue());
        return toLineageWithOrphanDataset(nodeId.asDatasetId());
      }
      UUID job = optionalUUID.get();
      log.debug("Attempting to get lineage for job '{}'", job);
      Set<JobData> jobData = getLineage(Collections.singleton(job), depth);

      // Ensure job data is not empty, an empty set cannot be passed to LineageDao.getCurrentRuns()
      // or
      // LineageDao.getCurrentRunsWithFacets().
      if (jobData.isEmpty()) {
        // Log warning, then return an orphan lineage graph; a graph should contain at most one
        // job->dataset relationship.
        log.warn(
            "Failed to get lineage for job '{}' associated with node '{}', returning orphan graph...",
            job,
            nodeId.getValue());
        return toLineageWithOrphanDataset(nodeId.asDatasetId());
      }

      for (JobData j : jobData) {
        Optional<Run> run = runDao.findRunByUuid(j.getCurrentRunUuid());
        run.ifPresent(j::setLatestRun);
      }

      Set<UUID> datasetIds =
          jobData.stream()
              .flatMap(
                  jd -> Stream.concat(jd.getInputUuids().stream(), jd.getOutputUuids().stream()))
              .collect(Collectors.toSet());
      Set<DatasetData> datasets = new HashSet<>();
      if (!datasetIds.isEmpty()) {
        datasets.addAll(this.getDatasetData(datasetIds));
      }

      if (nodeId.isDatasetType()) {
        DatasetId datasetId = nodeId.asDatasetId();
        DatasetData datasetData =
            this.getDatasetData(
                datasetId.getNamespace().getValue(), datasetId.getName().getValue());

        if (!datasetIds.contains(datasetData.getUuid())) {
          log.warn(
              "Found jobs {} which no longer share lineage with dataset '{}' - discarding",
              jobData.stream().map(JobData::getId).toList(),
              nodeId.getValue());
          return toLineageWithOrphanDataset(nodeId.asDatasetId());
        }
      }
      return toLineage(jobData, datasets);
    }
  }

  private Lineage toLineageWithOrphanDataset(@NonNull DatasetId datasetId) {
    final DatasetData datasetData =
        getDatasetData(datasetId.getNamespace().getValue(), datasetId.getName().getValue());
    return new Lineage(
        ImmutableSortedSet.of(
            Node.dataset().data(datasetData).id(NodeId.of(datasetData.getId())).build()));
  }

  private Lineage toLineage(Set<JobData> jobData, Set<DatasetData> datasets) {
    Set<Node> nodes = new LinkedHashSet<>();
    // build mapping for later
    Map<UUID, DatasetData> datasetById =
        datasets.stream().collect(Collectors.toMap(DatasetData::getUuid, Functions.identity()));

    Map<DatasetData, Set<UUID>> dsInputToJob = new HashMap<>();
    Map<DatasetData, Set<UUID>> dsOutputToJob = new HashMap<>();
    // build jobs
    Map<UUID, JobData> jobDataMap = Maps.uniqueIndex(jobData, JobData::getUuid);
    for (JobData data : jobData) {
      if (data == null) {
        log.error("Could not find job node for {}", jobData);
        continue;
      }

      Optional<JobData> parentJobData = getParentJobData(data.getParentJobUuid());
      parentJobData.ifPresent(
          parent -> {
            log.debug(
                "child: {}, parent: {} with UUID: {}",
                parent.getId().getName(),
                data.getParentJobName(),
                data);
          });

      Set<DatasetData> inputs =
          data.getInputUuids().stream()
              .map(datasetById::get)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());
      Set<DatasetData> outputs =
          data.getOutputUuids().stream()
              .map(datasetById::get)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());
      data.setInputs(buildDatasetId(inputs));
      data.setOutputs(buildDatasetId(outputs));

      inputs.forEach(
          ds -> dsInputToJob.computeIfAbsent(ds, e -> new HashSet<>()).add(data.getUuid()));
      outputs.forEach(
          ds -> dsOutputToJob.computeIfAbsent(ds, e -> new HashSet<>()).add(data.getUuid()));

      NodeId origin = NodeId.of(new JobId(data.getNamespace(), data.getName()));
      Node node =
          new Node(
              origin,
              NodeType.JOB,
              data,
              buildDatasetEdge(inputs, origin),
              buildDatasetEdge(origin, outputs));
      nodes.add(node);
    }

    for (DatasetData dataset : datasets) {
      NodeId origin = NodeId.of(new DatasetId(dataset.getNamespace(), dataset.getName()));
      Node node =
          new Node(
              origin,
              NodeType.DATASET,
              dataset,
              buildJobEdge(dsOutputToJob.get(dataset), origin, jobDataMap),
              buildJobEdge(origin, dsInputToJob.get(dataset), jobDataMap));
      nodes.add(node);
    }

    return new Lineage(Lineage.withSortedNodes(Graph.directed().nodes(nodes).build()));
  }

  private ImmutableSet<DatasetId> buildDatasetId(Set<DatasetData> datasetData) {
    if (datasetData == null) {
      return ImmutableSet.of();
    }
    return datasetData.stream()
        .map(ds -> new DatasetId(ds.getNamespace(), ds.getName()))
        .collect(ImmutableSet.toImmutableSet());
  }

  private ImmutableSet<DatasetVersionId> buildDatasetVersionId(
      Set<DatasetVersionData> datasetVersionData) {
    if (datasetVersionData == null) {
      return ImmutableSet.of();
    }
    return datasetVersionData.stream()
        .map(
            dv ->
                new DatasetVersionId(
                    dv.getDatasetVersion().getNamespace(),
                    dv.getDatasetVersion().getName(),
                    dv.getVersion().getValue()))
        .collect(ImmutableSet.toImmutableSet());
  }

  private ImmutableSet<Edge> buildJobEdge(
      NodeId origin, Set<UUID> uuids, Map<UUID, JobData> jobDataMap) {
    if (uuids == null) {
      return ImmutableSet.of();
    }
    return uuids.stream()
        .map(jobDataMap::get)
        .filter(Objects::nonNull)
        .map(j -> new Edge(origin, buildEdge(j)))
        .collect(ImmutableSet.toImmutableSet());
  }

  private ImmutableSet<Edge> buildJobEdge(
      Set<UUID> uuids, NodeId origin, Map<UUID, JobData> jobDataMap) {
    if (uuids == null) {
      return ImmutableSet.of();
    }
    return uuids.stream()
        .map(jobDataMap::get)
        .filter(Objects::nonNull)
        .map(j -> new Edge(buildEdge(j), origin))
        .collect(ImmutableSet.toImmutableSet());
  }

  private ImmutableSet<Edge> buildDatasetEdge(NodeId nodeId, Set<DatasetData> datasetData) {
    if (datasetData == null) {
      return ImmutableSet.of();
    }
    return datasetData.stream()
        .map(ds -> new Edge(nodeId, buildEdge(ds)))
        .collect(ImmutableSet.toImmutableSet());
  }

  private ImmutableSet<Edge> buildDatasetEdge(Set<DatasetData> datasetData, NodeId nodeId) {
    if (datasetData == null) {
      return ImmutableSet.of();
    }
    return datasetData.stream()
        .map(ds -> new Edge(buildEdge(ds), nodeId))
        .collect(ImmutableSet.toImmutableSet());
  }

  private ImmutableSet<Edge> buildDatasetVersionEdge(
      Set<DatasetVersionData> datasetVersionData, NodeId nodeId) {
    if (datasetVersionData == null) {
      return ImmutableSet.of();
    }
    return datasetVersionData.stream()
        .map(ds -> new Edge(buildEdge(ds), nodeId))
        .collect(ImmutableSet.toImmutableSet());
  }

  private ImmutableSet<Edge> buildDatasetVersionEdge(
      NodeId nodeId, Set<DatasetVersionData> datasetVersionData) {
    if (datasetVersionData == null) {
      return ImmutableSet.of();
    }
    return datasetVersionData.stream()
        .map(ds -> new Edge(nodeId, buildEdge(ds)))
        .collect(ImmutableSet.toImmutableSet());
  }

  private NodeId buildEdge(DatasetData ds) {
    return NodeId.of(new DatasetId(ds.getNamespace(), ds.getName()));
  }

  private NodeId buildEdge(DatasetVersionData ds) {
    return NodeId.of(
        ds.getDatasetVersion().getNamespace(),
        ds.getDatasetVersion().getName(),
        ds.getVersion().getValue());
  }

  private NodeId buildEdge(JobData e) {
    return NodeId.of(new JobId(e.getNamespace(), e.getName()));
  }

  public Optional<UUID> getJobUuid(NodeId nodeId) {
    if (nodeId.isJobType()) {
      JobId jobId = nodeId.asJobId();
      return jobDao
          .findJobByNameAsRow(jobId.getNamespace().getValue(), jobId.getName().getValue())
          .map(JobRow::getUuid);
    } else if (nodeId.isDatasetType()) {
      DatasetId datasetId = nodeId.asDatasetId();
      return getJobFromInputOrOutput(
          datasetId.getName().getValue(), datasetId.getNamespace().getValue());
    } else if (nodeId.isRunType()) {
      RunId runId = nodeId.asRunId();
      return Optional.of(runId.getValue());
    } else {
      throw new NodeIdNotFoundException(
          String.format("Node '%s' must be of type dataset, job, or run!", nodeId.getValue()));
    }
  }

  /**
   * Returns the upstream lineage for a given run. Recursively: run -> dataset version it read from
   * -> the run that produced it
   *
   * @param runId the run to get upstream lineage from
   * @param depth the maximum depth of the upstream lineage
   * @return the upstream lineage for that run up to `detph` levels
   */
  public UpstreamRunLineage upstream(@NotNull RunId runId, int depth) {
    List<UpstreamRunRow> upstreamRuns = getUpstreamRuns(runId.getValue(), depth);
    Map<RunId, List<UpstreamRunRow>> collect =
        upstreamRuns.stream().collect(groupingBy(r -> r.run().id(), LinkedHashMap::new, toList()));
    List<UpstreamRun> runs =
        collect.entrySet().stream()
            .map(
                row -> {
                  UpstreamRunRow upstreamRunRow = row.getValue().get(0);
                  List<DatasetSummary> inputs =
                      row.getValue().stream()
                          .map(UpstreamRunRow::input)
                          .filter(i -> i != null)
                          .collect(toList());
                  return new UpstreamRun(upstreamRunRow.job(), upstreamRunRow.run(), inputs);
                })
            .collect(toList());
    return new UpstreamRunLineage(runs);
  }

  private Lineage toRunLineage(Set<RunData> runData) {
    Set<Node> nodes = new LinkedHashSet<>();

    Set<DatasetVersionId> datasetVersionIds =
        runData.stream()
            .flatMap(
                rd ->
                    Stream.concat(
                        rd.getInputDatasetVersions().stream()
                            .map(InputDatasetVersion::getDatasetVersionId),
                        rd.getOutputDatasetVersions().stream()
                            .map(OutputDatasetVersion::getDatasetVersionId)))
            .collect(Collectors.toSet());
    log.info("DatasetVersionIds found in run data: {}", datasetVersionIds);

    Set<DatasetVersionData> datasetVersions = new HashSet<>();

    datasetVersions.addAll(
        this.getDatasetVersionData(
            datasetVersionIds.stream()
                .map(DatasetVersionId::getVersion)
                .collect(Collectors.toSet())));
    log.debug("Retrieved dataset data: {}", datasetVersions);

    Map<UUID, DatasetVersionData> datasetVersionById =
        datasetVersions.stream()
            .filter(dv -> dv.getVersion() != null) // Avoid null keys
            .collect(
                Collectors.toMap(
                    dv -> dv.getVersion().getValue(), // Use the version UUID as key
                    Function.identity(),
                    (existing, replacement) -> existing));

    Map<DatasetVersionData, Set<UUID>> dsInputToRun = new HashMap<>();
    Map<DatasetVersionData, Set<UUID>> dsOutputToRun = new HashMap<>();

    Map<UUID, RunData> runDataMap = Maps.uniqueIndex(runData, RunData::getUuid);
    for (RunData data : runData) {
      log.debug("Processing run data: {}", data);
      Set<DatasetVersionData> inputs =
          data.getInputDatasetVersions().stream()
              .map(InputDatasetVersion::getDatasetVersionId)
              .map(DatasetVersionId::getVersion)
              .map(datasetVersionById::get)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());

      Set<DatasetVersionData> outputs =
          data.getOutputDatasetVersions().stream()
              .map(OutputDatasetVersion::getDatasetVersionId)
              .map(DatasetVersionId::getVersion)
              .map(datasetVersionById::get)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());
      log.debug("Run '{}' inputs: {}, outputs: {}", data.getUuid(), inputs, outputs);

      inputs.forEach(
          ds -> dsInputToRun.computeIfAbsent(ds, e -> new HashSet<>()).add(data.getUuid()));
      outputs.forEach(
          ds -> dsOutputToRun.computeIfAbsent(ds, e -> new HashSet<>()).add(data.getUuid()));

      NodeId origin = NodeId.of(RunId.of(data.getUuid()));
      log.info(
          "dsInputToRun: {}, dsOutputToRun: {}, runDataMap: {}",
          dsInputToRun,
          dsOutputToRun,
          runDataMap);
      Node node =
          new Node(
              origin,
              NodeType.RUN,
              data,
              buildDatasetVersionEdge(inputs, origin),
              buildDatasetVersionEdge(origin, outputs));
      log.debug("Created node for run '{}': {}", data.getUuid(), node);
      nodes.add(node);
    }

    for (DatasetVersionData dataset : datasetVersions) {
      NodeId origin =
          NodeId.of(
              new DatasetVersionId(
                  dataset.getDatasetVersion().getNamespace(),
                  dataset.getDatasetVersion().getName(),
                  dataset.getVersion().getValue()));
      Node node =
          new Node(
              origin,
              NodeType.DATASET_VERSION,
              dataset,
              buildRunEdge(dsOutputToRun.get(dataset), origin, runDataMap),
              buildRunEdge(origin, dsInputToRun.get(dataset), runDataMap));
      nodes.add(node);
    }

    return new Lineage(Lineage.withSortedNodes(Graph.directed().nodes(nodes).build()));
  }

  private ImmutableSet<Edge> buildRunEdge(
      NodeId origin, Set<UUID> uuids, Map<UUID, RunData> runDataMap) {
    if (uuids == null) {
      return ImmutableSet.of();
    }
    return uuids.stream()
        .map(runDataMap::get)
        .filter(Objects::nonNull)
        .map(r -> new Edge(origin, buildEdge(r)))
        .collect(ImmutableSet.toImmutableSet());
  }

  private ImmutableSet<Edge> buildRunEdge(
      Set<UUID> uuids, NodeId origin, Map<UUID, RunData> runDataMap) {
    if (uuids == null) {
      return ImmutableSet.of();
    }
    return uuids.stream()
        .map(runDataMap::get)
        .filter(Objects::nonNull)
        .map(r -> new Edge(buildEdge(r), origin))
        .collect(ImmutableSet.toImmutableSet());
  }

  private NodeId buildEdge(RunData r) {
    return NodeId.of(RunId.of(r.getUuid()));
  }
}

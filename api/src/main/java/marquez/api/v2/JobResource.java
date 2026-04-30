/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.v2;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.Value;
import marquez.api.BaseResource;
import marquez.api.exceptions.JobNotFoundException;
import marquez.api.exceptions.JobVersionNotFoundException;
import marquez.api.exceptions.NamespaceNotFoundException;
import marquez.api.models.JobVersion;
import marquez.api.models.ResultsPage;
import marquez.common.models.FacetType;
import marquez.common.models.JobName;
import marquez.common.models.NamespaceName;
import marquez.common.models.RunId;
import marquez.common.models.RunState;
import marquez.common.models.Version;
import marquez.db.JobFacetsDao;
import marquez.db.JobVersionDao;
import marquez.db.RunFacetsDao;
import marquez.db.models.JobRow;
import marquez.service.ServiceFactory;
import marquez.service.models.Job;
import marquez.service.models.JobMeta;
import marquez.service.models.Run;
import marquez.service.models.RunMeta;

@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
public class JobResource extends BaseResource {

  private final JobVersionDao jobVersionDao;
  private final JobFacetsDao jobFacetsDao;
  private final RunFacetsDao runFacetsDao;

  public JobResource(
      @NonNull ServiceFactory serviceFactory,
      @NonNull JobVersionDao jobVersionDao,
      @NonNull JobFacetsDao jobFacetsDao,
      @NonNull RunFacetsDao runFacetsDao) {
    super(serviceFactory);
    this.jobVersionDao = jobVersionDao;
    this.jobFacetsDao = jobFacetsDao;
    this.runFacetsDao = runFacetsDao;
  }

  private UUID requireNamespaceUuid(NamespaceName namespaceName) {
    return jobService
        .findNamespaceUuidByName(namespaceName.getValue())
        .orElseThrow(() -> new NamespaceNotFoundException(namespaceName));
  }

  // --- Write endpoints -------------------------------------------------------

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @PUT
  @Path("/namespaces/{namespace}/jobs/{job}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response createOrUpdate(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("job") JobName jobName,
      @Valid JobMeta jobMeta) {
    throwIfNotExists(namespaceName);
    if (jobMeta.getRunId().isPresent()) {
      throwIfJobDoesNotMatchRun(
          jobMeta.getRunId().get(), namespaceName.getValue(), jobName.getValue());
    }
    throwIfDatasetsNotExist(jobMeta.getInputs());
    throwIfDatasetsNotExist(jobMeta.getOutputs());
    return Response.ok(jobService.createOrUpdate(namespaceName, jobName, jobMeta)).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @DELETE
  @Path("/namespaces/{namespace}/jobs/{job}")
  public Response delete(
      @PathParam("namespace") NamespaceName namespaceName, @PathParam("job") JobName jobName) {
    throwIfNotExists(namespaceName);
    Job job =
        jobService
            .findJobByName(namespaceName.getValue(), jobName.getValue())
            .orElseThrow(() -> new JobNotFoundException(jobName));
    jobService.delete(namespaceName.getValue(), job.getName().getValue());
    return Response.ok(job).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @POST
  @Path("/namespaces/{namespace}/jobs/{job}/runs")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response createRun(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("job") JobName jobName,
      @Valid RunMeta runMeta,
      @Context UriInfo uriInfo) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, jobName);
    throwIfExists(namespaceName, jobName, runMeta.getId().orElse(null));
    JobRow job =
        jobService
            .findJobByNameAsRow(namespaceName.getValue(), jobName.getValue())
            .orElseThrow(() -> new JobNotFoundException(jobName));
    final Run run = runService.createRun(namespaceName, job, runMeta);
    return Response.created(locationFor(uriInfo, run)).entity(run).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @POST
  @Path("/namespaces/{namespace}/jobs/{job}/tags/{tag}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response updateTag(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("job") JobName jobName,
      @PathParam("tag") String tag) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, jobName);
    jobService.updateJobTags(namespaceName.getValue(), jobName.getValue(), tag);
    Job job =
        jobService
            .findJobByName(namespaceName.getValue(), jobName.getValue())
            .orElseThrow(() -> new JobNotFoundException(jobName));
    return Response.ok(job).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @DELETE
  @Path("/namespaces/{namespace}/jobs/{job}/tags/{tag}")
  public Response deleteTag(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("job") JobName jobName,
      @PathParam("tag") String tag) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, jobName);
    jobService.deleteJobTags(namespaceName.getValue(), jobName.getValue(), tag);
    Job job =
        jobService
            .findJobByName(namespaceName.getValue(), jobName.getValue())
            .orElseThrow(() -> new JobNotFoundException(jobName));
    return Response.ok(job).build();
  }

  // --- Read endpoints (use denormalised tables where available) --------------

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/jobs")
  public Response listAllJobs(
      @QueryParam("lastRunStates") List<RunState> lastRunStates,
      @QueryParam("limit") @DefaultValue("100") @Min(0) int limit,
      @QueryParam("offset") @DefaultValue("0") @Min(0) int offset) {
    if (lastRunStates == null || lastRunStates.isEmpty()) {
      lastRunStates = new ArrayList<>();
      Collections.addAll(lastRunStates, RunState.values());
    }
    final List<Job> jobs = jobService.findAllWithRun(null, lastRunStates, limit, offset);
    final int totalCount = jobService.countForV2(null);
    return Response.ok(new ResultsPage<>("jobs", jobs, totalCount)).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/namespaces/{namespace}/jobs")
  public Response listJobs(
      @PathParam("namespace") NamespaceName namespaceName,
      @QueryParam("limit") @DefaultValue("100") @Min(0) int limit,
      @QueryParam("offset") @DefaultValue("0") @Min(0) int offset,
      @QueryParam("includeFacets") @DefaultValue("") Set<String> includeFacets) {
    UUID namespaceUuid = requireNamespaceUuid(namespaceName);
    List<Job> jobs = jobService.findAllJobsV2(namespaceUuid, limit, offset, includeFacets);
    int totalCount = jobService.countForV2(namespaceName.getValue());
    return Response.ok(new Jobs(jobs, totalCount)).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/namespaces/{namespace}/jobs/{job}")
  public Response getJob(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("job") JobName jobName,
      @QueryParam("includeFacets") @DefaultValue("") Set<String> includeFacets) {
    UUID namespaceUuid = requireNamespaceUuid(namespaceName);
    Job job =
        jobService
            .findJobByNameV2(namespaceUuid, jobName.getValue(), includeFacets)
            .orElseThrow(() -> new JobNotFoundException(jobName));
    return Response.ok(job).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/namespaces/{namespace}/jobs/{job}/versions")
  public Response listJobVersions(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("job") JobName jobName,
      @QueryParam("limit") @DefaultValue("100") @Min(0) int limit,
      @QueryParam("offset") @DefaultValue("0") @Min(0) int offset) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, jobName);
    final List<JobVersion> versions =
        jobVersionDao.findAllJobVersions(
            namespaceName.getValue(), jobName.getValue(), limit, offset);
    return Response.ok(new JobVersions(versions)).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/namespaces/{namespace}/jobs/{job}/versions/{version}")
  public Response getJobVersion(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("job") JobName jobName,
      @PathParam("version") Version version) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, jobName);
    final JobVersion jobVersion =
        jobVersionDao
            .findJobVersion(namespaceName.getValue(), jobName.getValue(), version.getValue())
            .orElseThrow(() -> new JobVersionNotFoundException(version));
    return Response.ok(jobVersion).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/namespaces/{namespace}/jobs/{job}/runs")
  public Response listRuns(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("job") JobName jobName,
      @QueryParam("limit") @DefaultValue("100") @Min(0) int limit,
      @QueryParam("offset") @DefaultValue("0") @Min(0) int offset,
      @QueryParam("includeFacets") Set<String> includeFacets) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, jobName);
    final List<Run> runs =
        runService.findAllWithFacets(
            namespaceName.getValue(), jobName.getValue(), limit, offset, includeFacets);
    final int totalCount = jobService.countJobRuns(namespaceName.getValue(), jobName.getValue());
    return Response.ok(new Runs(runs, totalCount)).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/jobs/runs/{id}/facets")
  public Response getRunFacets(
      @PathParam("id") RunId runId, @QueryParam("type") @NotNull FacetType type) {
    throwIfNotExists(runId);
    Object facets = null;
    switch (type) {
      case JOB:
        facets = jobFacetsDao.findJobFacetsByRunUuid(runId.getValue());
        break;
      case RUN:
        facets = runFacetsDao.findRunFacetsByRunUuid(runId.getValue());
        break;
      default:
        break;
    }
    return Response.ok(facets).build();
  }

  // --- Response wrappers ---------------------------------------------------

  @Value
  static class Jobs {
    @NonNull
    @JsonProperty("jobs")
    List<Job> value;

    @JsonProperty("totalCount")
    int totalCount;
  }

  @Value
  static class JobVersions {
    @NonNull
    @JsonProperty("versions")
    List<JobVersion> value;
  }

  @Value
  static class Runs {
    @NonNull
    @JsonProperty("runs")
    List<Run> value;

    @JsonProperty("totalCount")
    int totalCount;
  }
}

/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api;

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
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Value;
import marquez.api.exceptions.JobNotFoundException;
import marquez.api.exceptions.JobVersionNotFoundException;
import marquez.api.models.JobVersion;
import marquez.api.models.ResultsPage;
import marquez.common.models.FacetType;
import marquez.common.models.JobName;
import marquez.common.models.NamespaceName;
import marquez.common.models.RunId;
import marquez.common.models.RunState;
import marquez.common.models.TagName;
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

@Path("/api/v1")
public class JobResource extends BaseResource {
  private final JobVersionDao jobVersionDao;
  private final JobFacetsDao jobFacetsDao;
  private final RunFacetsDao runFacetsDao;

  public JobResource(
      @NonNull final ServiceFactory serviceFactory,
      @NonNull final JobVersionDao jobVersionDao,
      @NonNull JobFacetsDao jobFacetsDao,
      @NonNull RunFacetsDao runFacetsDao) {
    super(serviceFactory);
    this.jobVersionDao = jobVersionDao;
    this.jobFacetsDao = jobFacetsDao;
    this.runFacetsDao = runFacetsDao;
  }

  /**
   * @deprecated Prefer OpenLineage, see <a
   *     href="https://openlineage.io">https://openlineage.io</a>. This method is scheduled to be
   *     removed in release {@code 0.25.0}.
   */
  @Timed
  @ResponseMetered
  @ExceptionMetered
  @PUT
  @Path("/namespaces/{namespace}/jobs/{job}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
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

    final Job job = jobService.createOrUpdate(namespaceName, jobName, jobMeta);
    return Response.ok(job).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/namespaces/{namespace}/jobs/{job}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getJob(
      @PathParam("namespace") NamespaceName namespaceName, @PathParam("job") JobName jobName) {
    throwIfNotExists(namespaceName);

    final Job job =
        jobService
            .findWithDatasetsAndRun(namespaceName.getValue(), jobName.getValue())
            .orElseThrow(() -> new JobNotFoundException(jobName));
    return Response.ok(job).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/namespaces/{namespace}/jobs/{job}/versions/{version}")
  @Produces(MediaType.APPLICATION_JSON)
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
  @Path("/namespaces/{namespace}/jobs/{job}/versions")
  @Produces(MediaType.APPLICATION_JSON)
  public Response listJobVersions(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("job") JobName jobName,
      @QueryParam("limit") @DefaultValue("100") @Min(value = 0) int limit,
      @QueryParam("offset") @DefaultValue("0") @Min(value = 0) int offset) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, jobName);

    final List<JobVersion> jobVersions =
        jobVersionDao.findAllJobVersions(
            namespaceName.getValue(), jobName.getValue(), limit, offset);
    return Response.ok(new JobVersions(jobVersions)).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/jobs")
  @Produces(MediaType.APPLICATION_JSON)
  public Response list(
      @QueryParam("lastRunStates") List<RunState> lastRunStates,
      @QueryParam("limit") @DefaultValue("100") @Min(value = 0) int limit,
      @QueryParam("offset") @DefaultValue("0") @Min(value = 0) int offset) {
    return list(null, lastRunStates, limit, offset);
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/namespaces/{namespace}/jobs")
  @Produces(MediaType.APPLICATION_JSON)
  public Response list(
      @PathParam("namespace") NamespaceName namespaceName,
      @QueryParam("lastRunStates") List<RunState> lastRunStates,
      @QueryParam("limit") @DefaultValue("100") @Min(value = 0) int limit,
      @QueryParam("offset") @DefaultValue("0") @Min(value = 0) int offset) {
    final Optional<NamespaceName> namespaceOrNull = Optional.ofNullable(namespaceName);
    final String namespace = namespaceOrNull.map(NamespaceName::getValue).orElse(null);

    // default to all run states if not specified
    if (lastRunStates.isEmpty()) {
      lastRunStates = new ArrayList<>();
      Collections.addAll(lastRunStates, RunState.values());
    }

    final List<Job> jobs = jobService.findAllWithRun(namespace, lastRunStates, limit, offset);
    final int totalCount = jobService.countFor(namespace);
    return Response.ok(new ResultsPage<>("jobs", jobs, totalCount)).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @DELETE
  @Path("/namespaces/{namespace}/jobs/{job}")
  @Produces(MediaType.APPLICATION_JSON)
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
  @Path("namespaces/{namespace}/jobs/{job}/runs")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
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
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format(
                            "No such job with namespace %s and name %s",
                            namespaceName.getValue(), jobName.getValue())));

    final Run run = runService.createRun(namespaceName, job, runMeta);
    final URI runLocation = locationFor(uriInfo, run);
    return Response.created(runLocation).entity(run).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/namespaces/{namespace}/jobs/{job}/runs")
  @Produces(MediaType.APPLICATION_JSON)
  public Response listRuns(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("job") JobName jobName,
      @QueryParam("limit") @DefaultValue("100") @Min(value = 0) int limit,
      @QueryParam("offset") @DefaultValue("0") @Min(value = 0) int offset) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, jobName);

    final List<Run> runs =
        runService.findAll(namespaceName.getValue(), jobName.getValue(), limit, offset);
    final int totalCount = jobService.countJobRuns(namespaceName.getValue(), jobName.getValue());
    return Response.ok(new Runs(runs, totalCount)).build();
  }

  @Path("/jobs/runs/{id}")
  public RunResource runResourceRoot(@PathParam("id") RunId runId) {
    throwIfNotExists(runId);
    return new RunResource(runId, runService);
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Produces(MediaType.APPLICATION_JSON)
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
      case DATASET:
        // for future case if there's a need to add dataset facets to the endpoint
        break;
      default:
        break;
    }

    return Response.ok(facets).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @POST
  @Path("/namespaces/{namespace}/jobs/{job}/tags/{tag}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response updatetag(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("job") JobName jobName,
      @PathParam("tag") TagName tagName) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, jobName);

    jobService.updateJobTags(namespaceName.getValue(), jobName.getValue(), tagName.getValue());
    Job job =
        jobService
            .findJobByName(namespaceName.getValue(), jobName.getValue())
            .orElseThrow(() -> new JobNotFoundException(jobName));
    return Response.ok(job).build();
  }

  @ResponseMetered
  @ExceptionMetered
  @DELETE
  @Path("/namespaces/{namespace}/jobs/{job}/tags/{tag}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response deletetag(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("job") JobName jobName,
      @PathParam("tag") TagName tagName) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, jobName);

    jobService.deleteJobTags(namespaceName.getValue(), jobName.getValue(), tagName.getValue());
    Job job =
        jobService
            .findJobByName(namespaceName.getValue(), jobName.getValue())
            .orElseThrow(() -> new JobNotFoundException(jobName));
    return Response.ok(job).build();
  }

  @Value
  static class JobVersions {
    @NonNull
    @JsonProperty("versions")
    List<JobVersion> value;
  }

  @NoArgsConstructor
  @AllArgsConstructor
  @Getter
  public static class Runs {
    @NonNull
    @JsonProperty("runs")
    List<Run> value;

    @JsonProperty("totalCount")
    int totalCount;
  }
}

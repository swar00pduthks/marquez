/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.v2;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import marquez.api.BaseResource;
import marquez.api.models.Period;
import marquez.service.ServiceFactory;
import marquez.service.StatsService;

@Path("/api/v2/stats")
@Produces(MediaType.APPLICATION_JSON)
public class StatsResource extends BaseResource {

  private final StatsService statsService;

  public StatsResource(@NonNull ServiceFactory serviceFactory) {
    super(serviceFactory);
    this.statsService = serviceFactory.getStatsService();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/lineage-events")
  public Response getStats(
      @QueryParam("period") Period period, @QueryParam("timezone") String timezone) {
    if (Period.WEEK.equals(period) && (timezone == null || timezone.isEmpty())) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity("Timezone must be specified for period 'WEEK'")
          .build();
    }
    return Period.DAY.equals(period)
        ? Response.ok(statsService.getLastDayLineageMetrics()).build()
        : Period.WEEK.equals(period)
            ? Response.ok(statsService.getLastWeekLineageMetrics(timezone)).build()
            : Response.status(Response.Status.BAD_REQUEST).entity("Invalid period").build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/jobs")
  public Response getJobs(
      @QueryParam("period") Period period, @QueryParam("timezone") String timezone) {
    return Period.DAY.equals(period)
        ? Response.ok(statsService.getLastDayJobs()).build()
        : Period.WEEK.equals(period)
            ? Response.ok(statsService.getLastWeekJobs(timezone)).build()
            : Response.status(Response.Status.BAD_REQUEST).entity("Invalid period").build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/datasets")
  public Response getDatasets(
      @QueryParam("period") Period period, @QueryParam("timezone") String timezone) {
    return Period.DAY.equals(period)
        ? Response.ok(statsService.getLastDayDatasets()).build()
        : Period.WEEK.equals(period)
            ? Response.ok(statsService.getLastWeekDatasets(timezone)).build()
            : Response.status(Response.Status.BAD_REQUEST).entity("Invalid period").build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/sources")
  public Response getSources(
      @QueryParam("period") Period period, @QueryParam("timezone") String timezone) {
    return Period.DAY.equals(period)
        ? Response.ok(statsService.getLastDaySources()).build()
        : Period.WEEK.equals(period)
            ? Response.ok(statsService.getLastWeekSources(timezone)).build()
            : Response.status(Response.Status.BAD_REQUEST).entity("Invalid period").build();
  }
}

/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import marquez.api.models.Period;
import marquez.service.StatsService;

@Path("/api/v3/stats")
@Produces(MediaType.APPLICATION_JSON)
public class StatsResourceV3 {

  private final StatsService statsService;

  public StatsResourceV3(StatsService statsService) {
    this.statsService = statsService;
  }

  @GET
  @Path("/lineage-events")
  public Response getLineageMetrics(
      @QueryParam("period") @DefaultValue("DAY") Period period,
      @QueryParam("timezone") String timezone) {
    return (period == Period.DAY
        ? Response.ok(statsService.getLastDayLineageMetrics()).build()
        : Response.ok(statsService.getLastWeekLineageMetrics(timezone)).build());
  }

  @GET
  @Path("/jobs")
  public Response getJobs(
      @QueryParam("period") @DefaultValue("DAY") Period period,
      @QueryParam("timezone") String timezone) {
    return (period == Period.DAY
        ? Response.ok(statsService.getLastDayJobs()).build()
        : Response.ok(statsService.getLastWeekJobs(timezone)).build());
  }

  @GET
  @Path("/datasets")
  public Response getDatasets(
      @QueryParam("period") @DefaultValue("DAY") Period period,
      @QueryParam("timezone") String timezone) {
    return (period == Period.DAY
        ? Response.ok(statsService.getLastDayDatasets()).build()
        : Response.ok(statsService.getLastWeekDatasets(timezone)).build());
  }

  @GET
  @Path("/sources")
  public Response getSources(
      @QueryParam("period") @DefaultValue("DAY") Period period,
      @QueryParam("timezone") String timezone) {
    return (period == Period.DAY
        ? Response.ok(statsService.getLastDaySources()).build()
        : Response.ok(statsService.getLastWeekSources(timezone)).build());
  }
}

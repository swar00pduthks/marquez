/*
 * Copyright 2018-2026 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.v2;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Set;
import marquez.api.BaseResource;
import marquez.service.ServiceFactory;
import marquez.service.models.NodeId;

@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
public class LineageResource extends BaseResource {
  private static final String DEFAULT_DEPTH = "20";

  public LineageResource(ServiceFactory serviceFactory) {
    super(serviceFactory);
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/lineage")
  public Response getLineage(
      @QueryParam("nodeId") @NotNull NodeId nodeId,
      @QueryParam("depth") @DefaultValue(DEFAULT_DEPTH) int depth,
      @QueryParam("aggregateToParentRun") @DefaultValue("false") boolean aggregateToParentRun,
      @QueryParam("includeFacets") Set<String> includeFacets) {
    throwIfNotExists(nodeId);
    return Response.ok(lineageService.lineageV2(nodeId, depth, aggregateToParentRun, includeFacets))
        .build();
  }
}

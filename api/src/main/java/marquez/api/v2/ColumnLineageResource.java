/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.v2;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.api.BaseResource;
import marquez.service.ServiceFactory;
import marquez.service.exceptions.NodeIdNotFoundException;
import marquez.service.models.NodeId;

@Slf4j
@Path("/api/v2/column-lineage")
@Produces(APPLICATION_JSON)
public class ColumnLineageResource extends BaseResource {

  private static final String DEFAULT_DEPTH = "20";

  public ColumnLineageResource(@NonNull ServiceFactory serviceFactory) {
    super(serviceFactory);
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  public Response getLineage(
      @QueryParam("nodeId") String nodeIdRaw,
      @QueryParam("depth") @DefaultValue(DEFAULT_DEPTH) int depth,
      @QueryParam("withDownstream") @DefaultValue("false") boolean withDownstream) {
    try {
      if (nodeIdRaw == null || nodeIdRaw.isBlank()) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(Map.of("error", "Missing required query param: nodeId"))
            .type(MediaType.APPLICATION_JSON)
            .build();
      }
      NodeId nodeId = NodeId.of(nodeIdRaw);
      if (nodeId.hasVersion() && withDownstream) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(Map.of("error", "Node version cannot be specified when withDownstream is true"))
            .type(MediaType.APPLICATION_JSON)
            .build();
      }
      return Response.ok(columnLineageService.lineage(nodeId, depth, withDownstream)).build();
    } catch (IllegalArgumentException e) {
      log.warn("Invalid NodeId: {}", nodeIdRaw, e);
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(Map.of("error", "Invalid nodeId format", "message", e.getMessage()))
          .type(MediaType.APPLICATION_JSON)
          .build();
    } catch (NodeIdNotFoundException e) {
      log.warn("Node not found: {}", nodeIdRaw, e);
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "Node not found", "nodeId", nodeIdRaw))
          .type(MediaType.APPLICATION_JSON)
          .build();
    }
  }
}

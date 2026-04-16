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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import lombok.NonNull;
import lombok.Value;
import marquez.api.BaseResource;
import marquez.api.exceptions.SourceNotFoundException;
import marquez.common.models.SourceName;
import marquez.service.ServiceFactory;
import marquez.service.models.Source;
import marquez.service.models.SourceMeta;

@Path("/api/v2/sources")
@Produces(MediaType.APPLICATION_JSON)
public class SourceResource extends BaseResource {

  public SourceResource(@NonNull ServiceFactory serviceFactory) {
    super(serviceFactory);
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @PUT
  @Path("{source}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response createOrUpdate(@PathParam("source") SourceName name, @Valid SourceMeta meta) {
    return Response.ok(sourceService.createOrUpdate(name, meta)).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("{source}")
  public Response get(@PathParam("source") SourceName name) {
    Source source =
        sourceService.findBy(name.getValue()).orElseThrow(() -> new SourceNotFoundException(name));
    return Response.ok(source).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  public Response list(
      @QueryParam("limit") @DefaultValue("100") @Min(0) int limit,
      @QueryParam("offset") @DefaultValue("0") @Min(0) int offset) {
    return Response.ok(new Sources(sourceService.findAll(limit, offset))).build();
  }

  @Value
  static class Sources {
    @NonNull
    @JsonProperty("sources")
    List<Source> value;
  }
}

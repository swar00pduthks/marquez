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
import jakarta.ws.rs.DELETE;
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
import java.util.Optional;
import lombok.NonNull;
import lombok.Value;
import marquez.api.BaseResource;
import marquez.api.exceptions.NamespaceNotFoundException;
import marquez.api.filter.exclusions.Exclusions;
import marquez.api.filter.exclusions.ExclusionsConfig;
import marquez.common.models.NamespaceName;
import marquez.service.ServiceFactory;
import marquez.service.models.Namespace;
import marquez.service.models.NamespaceMeta;

@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
public class NamespaceResource extends BaseResource {

  public NamespaceResource(@NonNull ServiceFactory serviceFactory) {
    super(serviceFactory);
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @PUT
  @Path("/namespaces/{namespace}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response createOrUpdate(
      @PathParam("namespace") NamespaceName name, @Valid NamespaceMeta meta) {
    return Response.ok(namespaceService.createOrUpdate(name, meta)).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/namespaces/{namespace}")
  public Response get(@PathParam("namespace") NamespaceName name) {
    Namespace ns =
        namespaceService
            .findBy(name.getValue())
            .orElseThrow(() -> new NamespaceNotFoundException(name));
    return Response.ok(ns).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/namespaces")
  public Response list(
      @QueryParam("limit") @DefaultValue("100") @Min(0) int limit,
      @QueryParam("offset") @DefaultValue("0") @Min(0) int offset) {
    List<Namespace> namespaces =
        Optional.ofNullable(Exclusions.namespaces())
            .map(ExclusionsConfig.NamespaceExclusions::getOnRead)
            .filter(ExclusionsConfig.OnRead::isEnabled)
            .map(ExclusionsConfig.OnRead::getPattern)
            .map(pattern -> namespaceService.findAllWithExclusion(pattern, limit, offset))
            .orElseGet(() -> namespaceService.findAll(limit, offset));
    return Response.ok(new Namespaces(namespaces)).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @DELETE
  @Path("/namespaces/{namespace}")
  public Response delete(@PathParam("namespace") NamespaceName name) {
    Namespace ns =
        namespaceService
            .findBy(name.getValue())
            .orElseThrow(() -> new NamespaceNotFoundException(name));
    datasetService.deleteByNamespaceName(ns.getName().getValue());
    jobService.deleteByNamespaceName(ns.getName().getValue());
    namespaceService.delete(ns.getName().getValue());
    return Response.ok(ns).build();
  }

  @Value
  static class Namespaces {
    @NonNull
    @JsonProperty("namespaces")
    List<Namespace> value;
  }
}

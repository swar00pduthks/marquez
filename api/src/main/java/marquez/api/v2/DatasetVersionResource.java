/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.v2;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.Value;
import marquez.api.BaseResource;
import marquez.api.exceptions.DatasetNotFoundException;
import marquez.api.exceptions.NamespaceNotFoundException;
import marquez.common.models.DatasetName;
import marquez.common.models.NamespaceName;
import marquez.service.ServiceFactory;
import marquez.service.models.DatasetVersion;

@Path("/api/v2/namespaces/{namespace}/datasets/{dataset}/versions")
@Produces(MediaType.APPLICATION_JSON)
public class DatasetVersionResource extends BaseResource {

  public DatasetVersionResource(@NonNull ServiceFactory serviceFactory) {
    super(serviceFactory);
  }

  private UUID requireNamespaceUuid(NamespaceName namespaceName) {
    return datasetService
        .findNamespaceUuidByName(namespaceName.getValue())
        .orElseThrow(() -> new NamespaceNotFoundException(namespaceName));
  }

  private UUID requireDatasetUuid(UUID namespaceUuid, DatasetName datasetName) {
    return datasetService
        .findDatasetUuidByName(namespaceUuid, datasetName.getValue())
        .orElseThrow(() -> new DatasetNotFoundException(datasetName));
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  public Response listDatasetVersions(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @QueryParam("limit") @DefaultValue("100") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("includeFacets") @DefaultValue("") Set<String> includeFacets) {
    UUID namespaceUuid = requireNamespaceUuid(namespaceName);
    UUID datasetUuid = requireDatasetUuid(namespaceUuid, datasetName);
    List<DatasetVersion> versions =
        datasetVersionService.findAllDatasetVersionsV2(datasetUuid, limit, offset, includeFacets);
    return Response.ok(new DatasetVersions(versions, versions.size())).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("/{version}")
  public Response getDatasetVersion(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @PathParam("version") String version,
      @QueryParam("includeFacets") @DefaultValue("") Set<String> includeFacets) {
    UUID namespaceUuid = requireNamespaceUuid(namespaceName);
    UUID datasetUuid = requireDatasetUuid(namespaceUuid, datasetName);
    DatasetVersion dv =
        datasetVersionService
            .findDatasetVersionByVersionV2(datasetUuid, version, includeFacets)
            .orElseThrow(() -> new NotFoundException("Dataset version not found: " + version));
    return Response.ok(dv).build();
  }

  @Value
  static class DatasetVersions {
    @NonNull
    @JsonProperty("versions")
    List<DatasetVersion> value;

    @JsonProperty("totalCount")
    int totalCount;
  }
}

/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.v2;

import static com.google.common.base.Preconditions.checkArgument;

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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import marquez.api.BaseResource;
import marquez.api.exceptions.DatasetNotFoundException;
import marquez.api.exceptions.NamespaceNotFoundException;
import marquez.common.models.DatasetName;
import marquez.common.models.FieldName;
import marquez.common.models.NamespaceName;
import marquez.common.models.TagName;
import marquez.service.ServiceFactory;
import marquez.service.models.Dataset;
import marquez.service.models.DatasetMeta;

@Slf4j
@Path("/api/v2/namespaces/{namespace}/datasets")
@Produces(MediaType.APPLICATION_JSON)
public class DatasetResource extends BaseResource {

  public DatasetResource(@NonNull ServiceFactory serviceFactory) {
    super(serviceFactory);
  }

  // --- Helpers ---------------------------------------------------------------

  /**
   * Look up namespace UUID from name; throws NamespaceNotFoundException (proper JSON 404) if
   * missing.
   */
  private UUID requireNamespaceUuid(NamespaceName namespaceName) {
    return datasetService
        .findNamespaceUuidByName(namespaceName.getValue())
        .orElseThrow(() -> new NamespaceNotFoundException(namespaceName));
  }

  // --- Write endpoints (delegate to V1 services — writes go to normalised tables) --------

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @PUT
  @Path("{dataset}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response createOrUpdate(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @Valid DatasetMeta datasetMeta) {
    throwIfNotExists(namespaceName);
    datasetMeta.getRunId().ifPresent(this::throwIfNotExists);
    throwIfSourceNotExists(datasetMeta.getSourceName());
    return Response.ok(datasetService.createOrUpdate(namespaceName, datasetName, datasetMeta))
        .build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @DELETE
  @Path("{dataset}")
  public Response delete(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName) {
    throwIfNotExists(namespaceName);
    Dataset dataset =
        datasetService
            .findDatasetByName(namespaceName.getValue(), datasetName.getValue())
            .orElseThrow(() -> new DatasetNotFoundException(datasetName));
    datasetService
        .delete(namespaceName.getValue(), datasetName.getValue())
        .orElseThrow(() -> new DatasetNotFoundException(datasetName));
    return Response.ok(dataset).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @POST
  @Path("/{dataset}/tags/{tag}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response tag(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @PathParam("tag") TagName tagName) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, datasetName);
    final Dataset dataset =
        datasetService.updateTags(
            namespaceName.getValue(), datasetName.getValue(), tagName.getValue());
    return Response.ok(dataset).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @DELETE
  @Path("/{dataset}/tags/{tag}")
  public Response deleteDatasetTag(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @PathParam("tag") TagName tagName) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, datasetName);
    datasetService.deleteDatasetTag(
        namespaceName.getValue(), datasetName.getValue(), tagName.getValue());
    Dataset dataset =
        datasetService
            .findDatasetByName(namespaceName.getValue(), datasetName.getValue())
            .orElseThrow(() -> new DatasetNotFoundException(datasetName));
    return Response.ok(dataset).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @POST
  @Path("/{dataset}/fields/{field}/tags/{tag}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response tagField(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @PathParam("field") FieldName fieldName,
      @PathParam("tag") TagName tagName) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, datasetName);
    throwIfNotExists(namespaceName, datasetName, fieldName);
    final Dataset dataset =
        datasetFieldService.updateTags(
            namespaceName.getValue(),
            datasetName.getValue(),
            fieldName.getValue(),
            tagName.getValue().toUpperCase(Locale.getDefault()));
    return Response.ok(dataset).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @DELETE
  @Path("/{dataset}/fields/{field}/tags/{tag}")
  public Response deleteTagField(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @PathParam("field") FieldName fieldName,
      @PathParam("tag") TagName tagName) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, datasetName);
    throwIfNotExists(namespaceName, datasetName, fieldName);
    datasetFieldService.deleteDatasetFieldTag(
        namespaceName.getValue(),
        datasetName.getValue(),
        fieldName.getValue(),
        tagName.getValue().toUpperCase(Locale.getDefault()));
    datasetFieldService.deleteDatasetVersionFieldTag(
        namespaceName.getValue(),
        datasetName.getValue(),
        fieldName.getValue(),
        tagName.getValue().toUpperCase(Locale.getDefault()));
    Dataset dataset =
        datasetService
            .findDatasetByName(namespaceName.getValue(), datasetName.getValue())
            .orElseThrow(() -> new DatasetNotFoundException(datasetName));
    return Response.ok(dataset).build();
  }

  // --- Read endpoints (use denormalised tables for better performance) --------

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  public Response list(
      @PathParam("namespace") NamespaceName namespaceName,
      @QueryParam("limit") @DefaultValue("100") @Min(0) int limit,
      @QueryParam("offset") @DefaultValue("0") @Min(0) int offset,
      @QueryParam("includeFacets") @DefaultValue("") Set<String> includeFacets) {
    checkArgument(limit >= 0, "limit must be >= 0");
    checkArgument(offset >= 0, "offset must be >= 0");
    UUID namespaceUuid = requireNamespaceUuid(namespaceName);
    List<Dataset> datasets =
        datasetService.findAllDatasetsV2(namespaceUuid, limit, offset, includeFacets);
    columnLineageService.enrichWithColumnLineage(datasets);
    int totalCount = datasetService.countDatasetsV2(namespaceName.getValue());
    return Response.ok(new Datasets(datasets, totalCount)).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("{dataset}")
  public Response getDataset(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @QueryParam("includeFacets") @DefaultValue("") Set<String> includeFacets) {
    UUID namespaceUuid = requireNamespaceUuid(namespaceName);
    Dataset dataset =
        datasetService
            .findDatasetByNameV2(namespaceUuid, datasetName.getValue(), includeFacets)
            .orElseThrow(() -> new DatasetNotFoundException(datasetName));
    columnLineageService.enrichWithColumnLineage(Arrays.asList(dataset));
    return Response.ok(dataset).build();
  }

  // --- Response wrappers -----------------------------------------------------

  @Value
  static class Datasets {
    @NonNull
    @JsonProperty("datasets")
    List<Dataset> value;

    @JsonProperty("totalCount")
    int totalCount;
  }
}

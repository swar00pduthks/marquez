/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api;

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
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import marquez.api.exceptions.DatasetNotFoundException;
import marquez.api.exceptions.DatasetVersionNotFoundException;
import marquez.api.models.ResultsPage;
import marquez.common.models.DatasetName;
import marquez.common.models.FieldName;
import marquez.common.models.NamespaceName;
import marquez.common.models.TagName;
import marquez.common.models.Version;
import marquez.service.ServiceFactory;
import marquez.service.models.Dataset;
import marquez.service.models.DatasetMeta;
import marquez.service.models.DatasetVersion;

@Slf4j
@Path("/api/v1/namespaces/{namespace}/datasets")
public class DatasetResource extends BaseResource {
  public DatasetResource(@NonNull final ServiceFactory serviceFactory) {
    super(serviceFactory);
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
  @Path("{dataset}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response createOrUpdate(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @Valid DatasetMeta datasetMeta) {
    throwIfNotExists(namespaceName);
    datasetMeta.getRunId().ifPresent(this::throwIfNotExists);
    throwIfSourceNotExists(datasetMeta.getSourceName());

    final Dataset dataset = datasetService.createOrUpdate(namespaceName, datasetName, datasetMeta);
    return Response.ok(dataset).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("{dataset}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getDataset(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName) {
    throwIfNotExists(namespaceName);

    Dataset dataset =
        datasetService
            .findWithTags(namespaceName.getValue(), datasetName.getValue())
            .orElseThrow(() -> new DatasetNotFoundException(datasetName));
    columnLineageService.enrichWithColumnLineage(Arrays.asList(dataset));
    return Response.ok(dataset).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("{dataset}/versions/{version}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getVersion(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @PathParam("version") Version version) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, datasetName);

    final DatasetVersion datasetVersion =
        datasetVersionService
            .findByWithRun(version.getValue())
            .orElseThrow(() -> new DatasetVersionNotFoundException(version));
    return Response.ok(datasetVersion).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Path("{dataset}/versions")
  @Produces(MediaType.APPLICATION_JSON)
  public Response listVersions(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @QueryParam("limit") @DefaultValue("100") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, datasetName);
    checkArgument(limit >= 0, "limit must be >= 0");
    checkArgument(offset >= 0, "offset must be >= 0");

    final List<DatasetVersion> datasetVersions =
        datasetVersionService.findAllWithRun(
            namespaceName.getValue(), datasetName.getValue(), limit, offset);

    final int totalCount =
        datasetVersionService.countDatasetVersions(
            namespaceName.getValue(), datasetName.getValue());
    return Response.ok(new DatasetVersions(datasetVersions, totalCount)).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response list(
      @PathParam("namespace") NamespaceName namespaceName,
      @QueryParam("limit") @DefaultValue("100") @Min(value = 0) int limit,
      @QueryParam("offset") @DefaultValue("0") @Min(value = 0) int offset) {
    throwIfNotExists(namespaceName);

    final List<Dataset> datasets =
        datasetService.findAllWithTags(namespaceName.getValue(), limit, offset);
    columnLineageService.enrichWithColumnLineage(datasets);
    final int totalCount = datasetService.countFor(namespaceName.getValue());
    return Response.ok(new ResultsPage<>("datasets", datasets, totalCount)).build();
  }

  @Timed
  @ResponseMetered
  @ExceptionMetered
  @DELETE
  @Path("{dataset}")
  @Produces(MediaType.APPLICATION_JSON)
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
  @Produces(MediaType.APPLICATION_JSON)
  public Response tag(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @PathParam("tag") TagName tagName) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, datasetName);

    log.info(
        "Successfully tagged dataset '{}' with '{}'.", datasetName.getValue(), tagName.getValue());

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
  @Produces(MediaType.APPLICATION_JSON)
  public Response deleteDatasetTag(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @PathParam("tag") TagName tagName) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, datasetName);

    log.info(
        "Deleted tag '{}' from dataset '{}' on namespace '{}'",
        tagName.getValue(),
        datasetName.getValue(),
        namespaceName.getValue());
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
  @Produces(MediaType.APPLICATION_JSON)
  public Response tagField(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @PathParam("field") FieldName fieldName,
      @PathParam("tag") TagName tagName) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, datasetName);
    throwIfNotExists(namespaceName, datasetName, fieldName);
    log.info(
        "Tagging field '{}' on dataset '{}' with '{}'.",
        fieldName.getValue(),
        datasetName.getValue(),
        tagName.getValue());
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
  @Produces(MediaType.APPLICATION_JSON)
  public Response deleteTagField(
      @PathParam("namespace") NamespaceName namespaceName,
      @PathParam("dataset") DatasetName datasetName,
      @PathParam("field") FieldName fieldName,
      @PathParam("tag") TagName tagName) {
    throwIfNotExists(namespaceName);
    throwIfNotExists(namespaceName, datasetName);
    throwIfNotExists(namespaceName, datasetName, fieldName);
    log.info(
        "Deleting Tag '{}' from field '{}' on dataset '{}' in namespace '{}'.",
        tagName.getValue(),
        fieldName.getValue(),
        datasetName.getValue(),
        namespaceName.getValue());

    // delete tag from field
    datasetFieldService.deleteDatasetFieldTag(
        namespaceName.getValue(),
        datasetName.getValue(),
        fieldName.getValue(),
        tagName.getValue().toUpperCase(Locale.getDefault()));
    // delete tag from dataset_versions
    datasetFieldService.deleteDatasetVersionFieldTag(
        namespaceName.getValue(),
        datasetName.getValue(),
        fieldName.getValue(),
        tagName.getValue().toUpperCase(Locale.getDefault()));
    // return entire dataset
    Dataset dataset =
        datasetService
            .findDatasetByName(namespaceName.getValue(), datasetName.getValue())
            .orElseThrow(() -> new DatasetNotFoundException(datasetName));
    return Response.ok(dataset).build();
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

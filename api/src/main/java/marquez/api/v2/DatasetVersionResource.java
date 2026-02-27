package marquez.api.v2;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import marquez.api.BaseResource;
import marquez.db.models.DatasetVersionRow;
import marquez.service.ServiceFactory;

@Path("/api/v2/namespaces/{namespace}/datasets/{dataset}/versions")
@Produces(MediaType.APPLICATION_JSON)
public class DatasetVersionResource extends BaseResource {
  public DatasetVersionResource(ServiceFactory serviceFactory) {
    super(serviceFactory);
  }

  @GET
  public Response listDatasetVersions(
      @PathParam("namespace") String namespace,
      @PathParam("dataset") String dataset,
      @QueryParam("limit") @DefaultValue("100") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset) {
    Optional<UUID> nsUuidOpt = datasetService.findNamespaceUuidByName(namespace);
    if (nsUuidOpt.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).entity("Namespace not found").build();
    }
    UUID namespaceUuid = nsUuidOpt.get();
    Optional<UUID> dsUuidOpt = datasetService.findDatasetUuidByName(namespaceUuid, dataset);
    if (dsUuidOpt.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).entity("Dataset not found").build();
    }
    UUID datasetUuid = dsUuidOpt.get();
    List<DatasetVersionRow> versions =
        datasetVersionService.findAllDatasetVersionsV2(datasetUuid, limit, offset);
    return Response.ok(versions).build();
  }

  @GET
  @Path("/{version}")
  public Response getDatasetVersion(
      @PathParam("namespace") String namespace,
      @PathParam("dataset") String dataset,
      @PathParam("version") String version) {
    Optional<UUID> nsUuidOpt = datasetService.findNamespaceUuidByName(namespace);
    if (nsUuidOpt.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).entity("Namespace not found").build();
    }
    UUID namespaceUuid = nsUuidOpt.get();
    Optional<UUID> dsUuidOpt = datasetService.findDatasetUuidByName(namespaceUuid, dataset);
    if (dsUuidOpt.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).entity("Dataset not found").build();
    }
    UUID datasetUuid = dsUuidOpt.get();
    Optional<DatasetVersionRow> versionOpt =
        datasetVersionService.findDatasetVersionByVersionV2(datasetUuid, version);
    if (versionOpt.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).entity("Dataset version not found").build();
    }
    return Response.ok(versionOpt.get()).build();
  }
}

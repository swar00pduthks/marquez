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
import marquez.db.models.DatasetRow;
import marquez.service.ServiceFactory;

@Path("/api/v2/namespaces/{namespace}/datasets")
@Produces(MediaType.APPLICATION_JSON)
public class DatasetResource extends BaseResource {
  public DatasetResource(ServiceFactory serviceFactory) {
    super(serviceFactory);
  }

  @GET
  public Response listDatasets(
      @PathParam("namespace") String namespace,
      @QueryParam("limit") @DefaultValue("100") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset) {
    // Resolve namespace UUID
    Optional<UUID> nsUuidOpt = datasetService.findNamespaceUuidByName(namespace);
    if (nsUuidOpt.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).entity("Namespace not found").build();
    }
    UUID namespaceUuid = nsUuidOpt.get();
    List<DatasetRow> datasets = datasetService.findAllDatasetsV2(namespaceUuid, limit, offset);
    return Response.ok(datasets).build();
  }

  @GET
  @Path("/{dataset}")
  public Response getDataset(
      @PathParam("namespace") String namespace, @PathParam("dataset") String dataset) {
    Optional<UUID> nsUuidOpt = datasetService.findNamespaceUuidByName(namespace);
    if (nsUuidOpt.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).entity("Namespace not found").build();
    }
    UUID namespaceUuid = nsUuidOpt.get();
    Optional<DatasetRow> dsOpt = datasetService.findDatasetByNameV2(namespaceUuid, dataset);
    if (dsOpt.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).entity("Dataset not found").build();
    }
    return Response.ok(dsOpt.get()).build();
  }
}

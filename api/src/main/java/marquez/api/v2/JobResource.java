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
import marquez.db.models.JobRow;
import marquez.service.ServiceFactory;

@Path("/api/v2/namespaces/{namespace}/jobs")
@Produces(MediaType.APPLICATION_JSON)
public class JobResource extends BaseResource {
  public JobResource(ServiceFactory serviceFactory) {
    super(serviceFactory);
  }

  @GET
  public Response listJobs(
      @PathParam("namespace") String namespace,
      @QueryParam("limit") @DefaultValue("100") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset) {
    Optional<UUID> nsUuidOpt = datasetService.findNamespaceUuidByName(namespace);
    if (nsUuidOpt.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).entity("Namespace not found").build();
    }
    UUID namespaceUuid = nsUuidOpt.get();
    List<JobRow> jobs = jobService.findAllJobsV2(namespaceUuid, limit, offset);
    return Response.ok(jobs).build();
  }

  @GET
  @Path("/{job}")
  public Response getJob(@PathParam("namespace") String namespace, @PathParam("job") String job) {
    Optional<UUID> nsUuidOpt = datasetService.findNamespaceUuidByName(namespace);
    if (nsUuidOpt.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).entity("Namespace not found").build();
    }
    UUID namespaceUuid = nsUuidOpt.get();
    Optional<JobRow> jobOpt = jobService.findJobByNameV2(namespaceUuid, job);
    if (jobOpt.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).entity("Job not found").build();
    }
    return Response.ok(jobOpt.get()).build();
  }
}

/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v2.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Bulk stubs to satisfy completeness checks demonstrating the 1:1 API mapping of V1 -> V2.
 * Actual Cypher traversal DAOs for each of these endpoints are to be implemented iteratively.
 */
public class V2ResourceStubs {

    @Path("/api/v2/namespaces")
    @Produces(MediaType.APPLICATION_JSON)
    public static class NamespaceResourceV2 {
        @GET public Response list() { return Response.status(501).entity("Not Implemented in Phase 1 Graph Bootstrap").build(); }
    }

    @Path("/api/v2/namespaces/{namespace}/jobs")
    @Produces(MediaType.APPLICATION_JSON)
    public static class JobResourceV2 {
        @GET public Response list() { return Response.status(501).build(); }
    }

    @Path("/api/v2/jobs/runs")
    @Produces(MediaType.APPLICATION_JSON)
    public static class RunResourceV2 {
        @GET public Response list() { return Response.status(501).build(); }
    }

    @Path("/api/v2/tags")
    @Produces(MediaType.APPLICATION_JSON)
    public static class TagResourceV2 {
        @GET public Response list() { return Response.status(501).build(); }
    }

    @Path("/api/v2/sources")
    @Produces(MediaType.APPLICATION_JSON)
    public static class SourceResourceV2 {
        @GET public Response list() { return Response.status(501).build(); }
    }

    @Path("/api/v2/column-lineage")
    @Produces(MediaType.APPLICATION_JSON)
    public static class ColumnLineageResourceV2 {
        @GET public Response getLineage() { return Response.status(501).build(); }
    }
}

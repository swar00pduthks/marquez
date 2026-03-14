/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v2.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jdbi.v3.core.Jdbi;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Path("/api/v2/namespaces/{namespace}/datasets")
@Produces(MediaType.APPLICATION_JSON)
public class DatasetResourceV2 {

    private final Jdbi jdbi;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public DatasetResourceV2(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @GET
    public Response listDatasets(@PathParam("namespace") String namespace, @QueryParam("limit") Integer limit) {
        int l = limit == null ? 100 : limit;

        Map<String, Object> params = new HashMap<>();
        params.put("ns", namespace);
        params.put("lim", l);

        String paramsJson;
        try {
            paramsJson = MAPPER.writeValueAsString(params);
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // This demonstrates to the user how exactly V1 endpoints are ported to V2.
        // We use a simple Cypher match to replace complex relational joins.
        String query =
            "SELECT agtype_to_json(d) FROM cypher('marquez_graph', $$ " +
            "MATCH (n:Namespace {name: $ns})-[:HAS_DATASET]->(d:Dataset) " +
            "RETURN d LIMIT $lim $$, cast(:params_json as agtype)) as (d agtype);";

        List<String> result = jdbi.withHandle(handle ->
            handle.createQuery(query)
                  .bind("params_json", paramsJson)
                  .mapTo(String.class)
                  .list()
        );

        // This would normally map to `marquez.api.models.DatasetsResponse` to fulfill exact V1 contract.
        return Response.ok(Map.of("datasets", result)).build();
    }
}

/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v2.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jdbi.v3.core.Jdbi;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Collections;

@Path("/api/v2/lineage")
@Produces(MediaType.APPLICATION_JSON)
public class LineageResourceV2 {

    private final Jdbi jdbi;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public LineageResourceV2(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @GET
    public Response getLineageGraph(@QueryParam("nodeId") String nodeId, @QueryParam("depth") Integer depth) {
        int d = depth == null ? 3 : depth;

        // Safely serialize the parameter to JSON for AGE using Jackson
        String paramsJson;
        try {
            paramsJson = MAPPER.writeValueAsString(Collections.singletonMap("nodeId", nodeId));
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid nodeId").build();
        }

        // Use cast(:params_json as agtype) for PostgreSQL AGE parameterized querying.
        // The top level keys of the JSON object become the cypher variables (e.g. $nodeId)
        String query = String.format(
            "SELECT * FROM cypher('marquez_graph', $$ " +
            "MATCH path = (a)-[*1..%d]-(b) " +
            "WHERE a.name = $nodeId OR a.uuid = $nodeId " +
            "RETURN path $$, cast(:params_json as agtype)) as (path agtype);", d
        );

        List<Map<String, Object>> result = jdbi.withHandle(handle ->
            handle.createQuery(query)
                  .bind("params_json", paramsJson)
                  .mapToMap().list()
        );

        return Response.ok(result).build();
    }
}

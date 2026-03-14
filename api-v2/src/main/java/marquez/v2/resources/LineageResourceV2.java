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
        if (d > 20) {
            d = 20; // Hard upper bound to prevent traversal DoS attacks
        }

        String paramsJson;
        try {
            paramsJson = MAPPER.writeValueAsString(Collections.singletonMap("nodeId", nodeId));
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid nodeId").build();
        }

        // Use agtype_to_json to serialize the path out of AGE directly, preventing JDBC mapping errors
        String query = String.format(
            "SELECT agtype_to_json(path) FROM cypher('marquez_graph', $$ " +
            "MATCH path = (a)-[*1..%d]-(b) " +
            "WHERE a.name = $nodeId OR a.uuid = $nodeId " +
            "RETURN path $$, cast(:params_json as agtype)) as (path agtype);", d
        );

        List<com.fasterxml.jackson.databind.JsonNode> result = jdbi.withHandle(handle ->
            handle.createQuery(query)
                  .bind("params_json", paramsJson)
                  .map((rs, ctx) -> {
                      try {
                          return MAPPER.readTree(rs.getString(1));
                      } catch (Exception e) {
                          return null;
                      }
                  })
                  .list()
        );

        return Response.ok(Map.of("graph", result)).build();
    }
}

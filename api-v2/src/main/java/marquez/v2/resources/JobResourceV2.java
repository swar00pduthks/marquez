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

@Path("/api/v2/namespaces/{namespace}/jobs")
@Produces(MediaType.APPLICATION_JSON)
public class JobResourceV2 {

    private final Jdbi jdbi;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JobResourceV2(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @GET
    public Response listJobs(@PathParam("namespace") String namespace, @QueryParam("limit") Integer limit) {
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

        String query =
            "SELECT agtype_to_json(j) FROM cypher('marquez_graph', $$ " +
            "MATCH (n:Namespace {name: $ns})-[:HAS_JOB]->(j:Job) " +
            "RETURN properties(j) LIMIT $lim $$, cast(:params_json as agtype)) as (j agtype);";

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

        return Response.ok(Map.of("jobs", result)).build();
    }
}

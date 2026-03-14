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
import java.util.HashMap;

@Path("/api/v2/tags")
@Produces(MediaType.APPLICATION_JSON)
public class TagResourceV2 {
    private final Jdbi jdbi;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TagResourceV2(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @GET
    public Response listTags(@QueryParam("limit") Integer limit) {
        int l = limit == null ? 100 : limit;
        Map<String, Object> params = new HashMap<>();
        params.put("lim", l);

        String paramsJson;
        try {
            paramsJson = MAPPER.writeValueAsString(params);
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        String query =
            "SELECT agtype_to_json(t) FROM cypher('marquez_graph', $$ " +
            "MATCH (t:Tag) " +
            "RETURN properties(t) LIMIT $lim $$, cast(:params_json as agtype)) as (t agtype);";

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

        return Response.ok(Map.of("tags", result)).build();
    }
}

/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

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

@Path("/api/v3/namespaces")
@Produces(MediaType.APPLICATION_JSON)
public class NamespaceResourceV3 {

    private final Jdbi jdbi;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public NamespaceResourceV3(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @GET
    public Response listNamespaces(@QueryParam("limit") Integer limit) {
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
            "SELECT agtype_to_json(n) FROM cypher('marquez_graph', $$ " +
            "MATCH (n:Namespace) " +
            "RETURN properties(n) LIMIT $lim $$, cast(:params_json as agtype)) as (n agtype);";

        List<com.fasterxml.jackson.databind.JsonNode> result = jdbi.withHandle(handle -> {
            handle.execute("LOAD 'age'; SET search_path = ag_catalog, \"$user\", public;");
            return handle.createQuery(query)
                  .bind("params_json", paramsJson)
                  .map((rs, ctx) -> {
                      try {
                          com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(rs.getString(1)); return root.get("props") != null ? root.get("props") : root;
                      } catch (Exception e) {
                          return null;
                      }
                  })
                  .list(); }
        );

        return Response.ok(Map.of("namespaces", result)).build();
    }

    @GET
    @Path("{namespace}")
    public Response getNamespace(@PathParam("namespace") String namespace) {
        Map<String, Object> params = new HashMap<>();
        params.put("ns", namespace);

        String paramsJson;
        try {
            paramsJson = MAPPER.writeValueAsString(params);
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }


        String query =
            "SELECT agtype_to_json(n) FROM cypher('marquez_graph', $$ " +
            "MATCH (n:Namespace {name: $ns}) " +
            "RETURN properties(n) $$, cast(:params_json as agtype)) as (n agtype);";

        List<com.fasterxml.jackson.databind.JsonNode> result = jdbi.withHandle(handle -> {
            handle.execute("LOAD 'age'; SET search_path = ag_catalog, \"$user\", public;");
            return handle.createQuery(query)
                  .bind("params_json", paramsJson)
                  .map((rs, ctx) -> {
                      try {
                          com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(rs.getString(1)); return root.get("props") != null ? root.get("props") : root;
                      } catch (Exception e) {
                          return null;
                      }
                  })
                  .list(); }
        );

        if (result.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(result.get(0)).build();
    }
}

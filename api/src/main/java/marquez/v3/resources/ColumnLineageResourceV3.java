/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;

@Path("/api/v3/column-lineage")
@Produces(MediaType.APPLICATION_JSON)
public class ColumnLineageResourceV3 {
  private final Jdbi jdbi;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public ColumnLineageResourceV3(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @GET
  public Response getLineage(
      @QueryParam("nodeId") String nodeId, @QueryParam("depth") Integer depth) {
    int d = depth == null ? 10 : depth;
    if (d > 20) {
      d = 20; // Hard upper bound to prevent traversal DoS attacks
    }
    String paramsJson;
    try {
      paramsJson = MAPPER.writeValueAsString(Collections.singletonMap("nodeId", nodeId));
    } catch (Exception e) {
      return Response.status(Response.Status.BAD_REQUEST).entity("Invalid nodeId").build();
    }

    // Follow the DERIVED_FROM edges to track column lineage across dataset fields

    String query =
        String.format(
            "SELECT agtype_to_json(path) FROM cypher('marquez_graph', $$ "
                + "MATCH path = (a:DatasetField)-[:DERIVED_FROM*1..%d]-(b:DatasetField) "
                + "WHERE a.id = $nodeId "
                + "RETURN path $$, cast(:params_json as agtype)) as (path agtype);",
            d);

    List<com.fasterxml.jackson.databind.JsonNode> result =
        jdbi.withHandle(
            handle -> {
              handle.execute("LOAD 'age'; SET search_path = ag_catalog, \"$user\", public;");
              return handle
                  .createQuery(query)
                  .bind("params_json", paramsJson)
                  .map(
                      (rs, ctx) -> {
                        try {
                          com.fasterxml.jackson.databind.JsonNode root =
                              MAPPER.readTree(rs.getString(1));
                          return root.get("props") != null ? root.get("props") : root;
                        } catch (Exception e) {
                          return null;
                        }
                      })
                  .list();
            });

    return Response.ok(Map.of("lineage", result)).build();
  }
}

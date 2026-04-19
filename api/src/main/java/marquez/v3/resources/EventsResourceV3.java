/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;

@Path("/api/v3/events")
@Produces(MediaType.APPLICATION_JSON)
public class EventsResourceV3 {

  private final Jdbi jdbi;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public EventsResourceV3(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @GET
  @Path("/lineage")
  public Response listEvents(
      @QueryParam("limit") Integer limit,
      @QueryParam("before") String before,
      @QueryParam("after") String after,
      @QueryParam("offset") Integer offset,
      @QueryParam("sortDirection") String sortDirection) {

    int l = limit == null ? 100 : limit;
    int o = offset == null ? 0 : offset;
    String sort = "DESC".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";

    StringBuilder sql = new StringBuilder("SELECT event FROM lineage_events WHERE 1=1 ");
    List<Object> args = new ArrayList<>();

    if (before != null) {
      sql.append("AND created_at <= CAST(? AS TIMESTAMP) ");
      args.add(before);
    }
    if (after != null) {
      sql.append("AND created_at >= CAST(? AS TIMESTAMP) ");
      args.add(after);
    }

    sql.append("ORDER BY created_at ").append(sort).append(" LIMIT ? OFFSET ?");
    args.add(l);
    args.add(o);

    List<JsonNode> finalResult =
        jdbi.withHandle(
            handle -> {
              var query = handle.createQuery(sql.toString());
              for (int i = 0; i < args.size(); i++) {
                query.bind(i, args.get(i));
              }
              return query
                  .map(
                      (rs, ctx) -> {
                        try {
                          return MAPPER.readTree(rs.getString("event"));
                        } catch (Exception e) {
                          return null;
                        }
                      })
                  .list();
            });

    return Response.ok(Map.of("events", finalResult)).build();
  }
}

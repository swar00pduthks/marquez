/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.db;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.HashMap;

public class GraphDao {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void initGraph(Jdbi jdbi, String graphName) {
        jdbi.useHandle(handle -> {
            handle.execute("LOAD 'age'; SET search_path = ag_catalog, \"$user\", public;");
            boolean exists = handle.createQuery("SELECT 1 FROM ag_graph WHERE name = :name")
                                   .bind("name", graphName)
                                   .mapTo(Boolean.class)
                                   .findFirst()
                                   .orElse(false);
            if (!exists) {
                handle.createUpdate("SELECT create_graph(:name)")
                      .bind("name", graphName)
                      .execute();
            }
        });
    }

    public void upsertNode(Handle handle, String graphName, String label, String matchKey, Map<String, Object> properties) {
        Map<String, Object> params = new HashMap<>();
        params.put("matchValue", properties.get(matchKey));
        params.put("props", properties);

        String paramsJson;
        try {
            paramsJson = MAPPER.writeValueAsString(params);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize properties to JSON", e);
        }

        handle.execute("LOAD 'age'; SET search_path = ag_catalog, \"$user\", public;");
        String query = String.format(
            "SELECT * FROM cypher('%s', $$ " +
            "MERGE (n:%s { %s: $matchValue }) " +
            "SET n += $props " +
            "RETURN n $$, cast(:params_json as agtype)) as (n agtype)",
            graphName, label, matchKey
        );

        handle.createUpdate(query)
              .bind("params_json", paramsJson)
              .execute();
    }

    public void upsertEdge(Handle handle, String graphName, String edgeLabel,
                           String fromLabel, String fromMatchKey, String fromMatchValue,
                           String toLabel, String toMatchKey, String toMatchValue) {

        Map<String, String> params = new HashMap<>();
        params.put("fromVal", fromMatchValue);
        params.put("toVal", toMatchValue);

        String paramsJson;
        try {
            paramsJson = MAPPER.writeValueAsString(params);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize properties to JSON", e);
        }

        handle.execute("LOAD 'age'; SET search_path = ag_catalog, \"$user\", public;");
        String query = String.format(
            "SELECT * FROM cypher('%s', $$ " +
            "MATCH (a:%s { %s: $fromVal }) " +
            "MATCH (b:%s { %s: $toVal }) " +
            "MERGE (a)-[r:%s]->(b) " +
            "RETURN r $$, cast(:params_json as agtype)) as (r agtype)",
            graphName,
            fromLabel, fromMatchKey,
            toLabel, toMatchKey,
            edgeLabel
        );

        handle.createUpdate(query)
              .bind("params_json", paramsJson)
              .execute();
    }
}

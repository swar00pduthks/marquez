/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v2.db;

import org.jdbi.v3.core.Jdbi;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.HashMap;

public class GraphDao {
    private final Jdbi jdbi;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public GraphDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void initGraph(String graphName) {
        jdbi.useHandle(handle -> {
            boolean exists = handle.createQuery("SELECT 1 FROM ag_graph WHERE name = :name")
                                   .bind("name", graphName)
                                   .mapTo(Boolean.class)
                                   .findFirst()
                                   .orElse(false);
            if (!exists) {
                // graphName is a safe internal constant, but we still bind
                handle.execute("SELECT create_graph(:name)", graphName);
            }
        });
    }

    public void upsertNode(String graphName, String label, String matchKey, Map<String, Object> properties) {
        // AGE unpacks JSON parameters to top-level cypher variables.
        // We structure our params map to have `matchValue` and `props`.
        Map<String, Object> params = new HashMap<>();
        params.put("matchValue", properties.get(matchKey));
        params.put("props", properties);

        String paramsJson;
        try {
            paramsJson = MAPPER.writeValueAsString(params);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize properties to JSON", e);
        }

        // String interpolation ONLY for graphName, label, and matchKey which are controlled by us (static strings).
        // Values and dynamic properties are passed via parameterized JSON cast to agtype.
        // In AGE, the keys of the JSON object become top-level Cypher variables (e.g. $matchValue, $props).
        String query = String.format(
            "SELECT * FROM cypher('%s', $$ " +
            "MERGE (n:%s { %s: $matchValue }) " +
            "SET n += $props " +
            "RETURN n $$, cast(:params_json as agtype)) as (n agtype)",
            graphName, label, matchKey
        );

        jdbi.useHandle(handle -> handle.execute(query, paramsJson));
    }

    public void upsertEdge(String graphName, String edgeLabel,
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

        // String interpolation ONLY for static labels and keys.
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
        jdbi.useHandle(handle -> handle.execute(query, paramsJson));
    }
}

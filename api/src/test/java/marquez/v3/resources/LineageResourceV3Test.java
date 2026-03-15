/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.statement.Query;
import org.mockito.ArgumentCaptor;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

public class LineageResourceV3Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testGetLineageGraphWithAggregateToParentRun() throws Exception {
        Jdbi mockJdbi = mock(Jdbi.class);
        Handle mockHandle = mock(Handle.class);
        Query mockQuery = mock(Query.class);

        // Mock Jdbi to return our mocked Handle
        doAnswer(invocation -> {
            HandleCallback callback = invocation.getArgument(0);
            return callback.withHandle(mockHandle);
        }).when(mockJdbi).withHandle(any());

        // Capture the executed query string
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        when(mockHandle.createQuery(queryCaptor.capture())).thenReturn(mockQuery);
        when(mockQuery.bind(anyString(), anyString())).thenReturn(mockQuery);

        // Mock a single JSON result from PostgreSQL AGE `agtype_to_json()`
        org.jdbi.v3.core.result.ResultIterable mockResultIterable = mock(org.jdbi.v3.core.result.ResultIterable.class);
        when(mockQuery.map(any(org.jdbi.v3.core.mapper.RowMapper.class))).thenReturn(mockResultIterable);

        // Simulate AGE returning a JSON string for a parent run path
        String mockAgeJson = "{\"id\": \"parent-123\", \"props\": {\"name\": \"parent-job\", \"namespace\": \"test\"}}";
        when(mockResultIterable.list()).thenReturn(Collections.singletonList(MAPPER.readTree(mockAgeJson)));

        // We use OpenLineageResourceV3 since getLineageGraph is merged there
        marquez.v3.db.GraphDao mockDao = mock(marquez.v3.db.GraphDao.class);
        OpenLineageResourceV3 resource = new OpenLineageResourceV3(mockJdbi, mockDao);

        // Call GET lineage with aggregateToParentRun = true
        Response response = resource.getLineageGraph("test-node-id", 5, true);

        assertNotNull(response);
        assertEquals(200, response.getStatus());

        // Verify the response entity contains the parsed JSON (not double-encoded strings)
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertTrue(entity.containsKey("graph"));
        List<Object> graphList = (List<Object>) entity.get("graph");
        assertEquals(1, graphList.size());
        assertEquals("parent-123", ((com.fasterxml.jackson.databind.JsonNode)graphList.get(0)).get("id").asText());

        // Verify that the correct Cypher query was executed (it MUST contain the aggregation filter)
        String executedQuery = queryCaptor.getValue();
        assertTrue(executedQuery.contains("MATCH path = (a)-[*1..5]-(b)"));
        assertTrue(executedQuery.contains("AND NOT (b)<-[:HAS_PARENT]-(:Run)"), "Query must contain the HAS_PARENT filter for aggregateToParentRun=true");
    }

    @Test
    public void testGetLineageGraphWithoutAggregateToParentRun() throws Exception {
        Jdbi mockJdbi = mock(Jdbi.class);
        Handle mockHandle = mock(Handle.class);
        Query mockQuery = mock(Query.class);

        doAnswer(invocation -> {
            HandleCallback callback = invocation.getArgument(0);
            return callback.withHandle(mockHandle);
        }).when(mockJdbi).withHandle(any());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        when(mockHandle.createQuery(queryCaptor.capture())).thenReturn(mockQuery);
        when(mockQuery.bind(anyString(), anyString())).thenReturn(mockQuery);

        org.jdbi.v3.core.result.ResultIterable mockResultIterable = mock(org.jdbi.v3.core.result.ResultIterable.class);
        when(mockQuery.map(any(org.jdbi.v3.core.mapper.RowMapper.class))).thenReturn(mockResultIterable);
        when(mockResultIterable.list()).thenReturn(Collections.emptyList());

        marquez.v3.db.GraphDao mockDao = mock(marquez.v3.db.GraphDao.class);
        OpenLineageResourceV3 resource = new OpenLineageResourceV3(mockJdbi, mockDao);

        // Call GET lineage with aggregateToParentRun = false
        Response response = resource.getLineageGraph("test-node-id", 5, false);

        assertNotNull(response);
        assertEquals(200, response.getStatus());

        // Verify that the normal Cypher query was executed (it MUST NOT contain the aggregation filter)
        String executedQuery = queryCaptor.getValue();
        assertTrue(executedQuery.contains("MATCH path = (a)-[*1..5]-(b)"));
        assertTrue(!executedQuery.contains("AND NOT (b)<-[:HAS_PARENT]-(:Run)"), "Query must NOT contain the HAS_PARENT filter for aggregateToParentRun=false");
    }
}

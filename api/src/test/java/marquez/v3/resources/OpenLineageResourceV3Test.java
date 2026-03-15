/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleConsumer;

import marquez.v3.db.GraphDao;
import marquez.service.models.LineageEvent;
import jakarta.ws.rs.core.Response;

public class OpenLineageResourceV3Test {

    @Test
    public void testCreateLineage() {
        GraphDao mockDao = mock(GraphDao.class);
        Jdbi mockJdbi = mock(Jdbi.class);
        Handle mockHandle = mock(Handle.class);

        // Stub the useTransaction method to immediately execute the callback with the mock handle
        doAnswer(invocation -> {
            HandleConsumer<Exception> callback = invocation.getArgument(0);
            callback.useHandle(mockHandle);
            return null;
        }).when(mockJdbi).useTransaction(any());

        OpenLineageResourceV3 resource = new OpenLineageResourceV3(mockJdbi, mockDao);

        LineageEvent.Job mockJob = mock(LineageEvent.Job.class);
        when(mockJob.getNamespace()).thenReturn("test-namespace");
        when(mockJob.getName()).thenReturn("test-job");

        LineageEvent.Run mockRun = mock(LineageEvent.Run.class);
        when(mockRun.getRunId()).thenReturn("uuid-1234");

        LineageEvent mockEvent = mock(LineageEvent.class);
        when(mockEvent.getJob()).thenReturn(mockJob);
        when(mockEvent.getRun()).thenReturn(mockRun);
        when(mockEvent.getInputs()).thenReturn(null);
        when(mockEvent.getOutputs()).thenReturn(null);

        Response response = resource.createLineage(mockEvent);

        assertNotNull(response);
        assertEquals(201, response.getStatus());

        // Verify nodes were inserted using the transaction handle
        verify(mockDao, atLeastOnce()).upsertNode(eq(mockHandle), eq("marquez_graph"), eq("Namespace"), eq("name"), anyMap());
        verify(mockDao, atLeastOnce()).upsertNode(eq(mockHandle), eq("marquez_graph"), eq("Job"), eq("fqn"), anyMap());
        verify(mockDao, atLeastOnce()).upsertNode(eq(mockHandle), eq("marquez_graph"), eq("Run"), eq("uuid"), anyMap());
    }

    @Test
    public void testCreateLineageNullPayloads() {
        GraphDao mockDao = mock(GraphDao.class);
        Jdbi mockJdbi = mock(Jdbi.class);
        OpenLineageResourceV3 resource = new OpenLineageResourceV3(mockJdbi, mockDao);

        // Null event
        Response res1 = resource.createLineage(null);
        assertEquals(400, res1.getStatus());

        // Null Job
        LineageEvent mockEvent = mock(LineageEvent.class);
        when(mockEvent.getJob()).thenReturn(null);
        Response res2 = resource.createLineage(mockEvent);
        assertEquals(400, res2.getStatus());
    }
}

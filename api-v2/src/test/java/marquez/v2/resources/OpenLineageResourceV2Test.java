/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v2.resources;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;
import marquez.v2.db.GraphDao;
import marquez.service.models.LineageEvent;
import jakarta.ws.rs.core.Response;

public class OpenLineageResourceV2Test {

    @Test
    public void testCreateLineage() {
        GraphDao mockDao = mock(GraphDao.class);
        OpenLineageResourceV2 resource = new OpenLineageResourceV2(mockDao);

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
        assert(response.getStatus() == 201);

        // Verify namespace, job, and run nodes were upserted
        verify(mockDao, atLeastOnce()).upsertNode(eq("marquez_graph"), eq("Namespace"), eq("name"), anyMap());
        verify(mockDao, atLeastOnce()).upsertNode(eq("marquez_graph"), eq("Job"), eq("fqn"), anyMap());
        verify(mockDao, atLeastOnce()).upsertNode(eq("marquez_graph"), eq("Run"), eq("uuid"), anyMap());
    }
}

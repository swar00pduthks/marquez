/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.Statement;
import marquez.service.models.LineageEvent;
import marquez.v3.db.GraphWriter;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link OpenLineageResourceV3} using mocks (no database required). */
public class OpenLineageResourceV3Test {

  private marquez.service.OpenLineageService mockOpenLineageService;
  private GraphWriter mockGraphWriter;
  private Jdbi mockJdbi;
  private Handle mockHandle;
  private Connection mockConn;

  @BeforeEach
  void setUp() throws Exception {
    mockOpenLineageService = mock(marquez.service.OpenLineageService.class);
    mockGraphWriter = mock(GraphWriter.class);
    mockJdbi = mock(Jdbi.class);
    mockHandle = mock(Handle.class);
    mockConn = mock(Connection.class);
    Statement mockStmt = mock(Statement.class);

    when(mockHandle.getConnection()).thenReturn(mockConn);
    when(mockConn.createStatement()).thenReturn(mockStmt);

    // Stub useTransaction to execute the callback immediately with the mock handle
    doAnswer(
            invocation -> {
              HandleConsumer<Exception> callback = invocation.getArgument(0);
              callback.useHandle(mockHandle);
              return null;
            })
        .when(mockJdbi)
        .useTransaction(any());

    // GraphWriter.writeEvent is void – default mock behaviour (do nothing) is correct
    doNothing().when(mockGraphWriter).writeEvent(any(), any());
  }

  @Test
  void testCreateLineage_validEvent_returns201() throws Exception {
    OpenLineageResourceV3 resource =
        new OpenLineageResourceV3(mockJdbi, mockGraphWriter, mockOpenLineageService);

    LineageEvent.Job mockJob = mock(LineageEvent.Job.class);
    when(mockJob.getNamespace()).thenReturn("test-namespace");
    when(mockJob.getName()).thenReturn("test-job");

    LineageEvent.Run mockRun = mock(LineageEvent.Run.class);
    when(mockRun.getRunId()).thenReturn("550e8400-e29b-41d4-a716-446655440000");

    LineageEvent mockEvent = mock(LineageEvent.class);
    when(mockEvent.getJob()).thenReturn(mockJob);
    when(mockEvent.getRun()).thenReturn(mockRun);
    when(mockEvent.getInputs()).thenReturn(null);
    when(mockEvent.getOutputs()).thenReturn(null);

    Response response = resource.createLineage(mockEvent);

    assertNotNull(response);
    assertEquals(201, response.getStatus());

    // Graph write must be called synchronously before returning 201
    verify(mockGraphWriter).writeEvent(mockHandle, mockEvent);
  }

  @Test
  void testCreateLineage_nullEvent_returns400() {
    OpenLineageResourceV3 resource =
        new OpenLineageResourceV3(mockJdbi, mockGraphWriter, mockOpenLineageService);

    Response response = resource.createLineage(null);

    assertEquals(400, response.getStatus());
  }

  @Test
  void testCreateLineage_nullJob_returns400() {
    OpenLineageResourceV3 resource =
        new OpenLineageResourceV3(mockJdbi, mockGraphWriter, mockOpenLineageService);

    LineageEvent mockEvent = mock(LineageEvent.class);
    when(mockEvent.getJob()).thenReturn(null);

    Response response = resource.createLineage(mockEvent);

    assertEquals(400, response.getStatus());
  }

  @Test
  void testCreateLineage_nullRun_returns400() {
    OpenLineageResourceV3 resource =
        new OpenLineageResourceV3(mockJdbi, mockGraphWriter, mockOpenLineageService);

    LineageEvent.Job mockJob = mock(LineageEvent.Job.class);
    when(mockJob.getNamespace()).thenReturn("test-namespace");
    when(mockJob.getName()).thenReturn("test-job");

    LineageEvent mockEvent = mock(LineageEvent.class);
    when(mockEvent.getJob()).thenReturn(mockJob);
    when(mockEvent.getRun()).thenReturn(null);

    Response response = resource.createLineage(mockEvent);

    assertEquals(400, response.getStatus());
  }
}

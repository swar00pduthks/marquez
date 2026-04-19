/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v3.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

public class NamespaceResourceV3Test {

  @Test
  public void testListNamespaces() throws Exception {
    Jdbi mockJdbi = mock(Jdbi.class);
    Handle mockHandle = mock(Handle.class);
    Connection mockConn = mock(Connection.class);
    PreparedStatement mockPs = mock(PreparedStatement.class);
    ResultSet mockRs = mock(ResultSet.class);
    Statement mockStmt = mock(Statement.class);

    when(mockHandle.getConnection()).thenReturn(mockConn);
    when(mockConn.createStatement()).thenReturn(mockStmt);
    when(mockConn.prepareStatement(anyString())).thenReturn(mockPs);
    when(mockPs.executeQuery()).thenReturn(mockRs);
    when(mockRs.next()).thenReturn(false);

    doAnswer(
            invocation -> {
              HandleCallback callback = invocation.getArgument(0);
              return callback.withHandle(mockHandle);
            })
        .when(mockJdbi)
        .withHandle(any());

    NamespaceResourceV3 resource = new NamespaceResourceV3(mockJdbi);
    Response response = resource.listNamespaces(10);

    assertNotNull(response);
    assertEquals(200, response.getStatus());
  }

  @Test
  public void testGetNamespace() throws Exception {
    Jdbi mockJdbi = mock(Jdbi.class);
    Handle mockHandle = mock(Handle.class);
    Connection mockConn = mock(Connection.class);
    PreparedStatement mockPs = mock(PreparedStatement.class);
    ResultSet mockRs = mock(ResultSet.class);

    Statement mockStmt = mock(Statement.class);

    when(mockHandle.getConnection()).thenReturn(mockConn);
    when(mockConn.createStatement()).thenReturn(mockStmt);
    when(mockConn.prepareStatement(anyString())).thenReturn(mockPs);
    when(mockPs.executeQuery()).thenReturn(mockRs);
    when(mockRs.next()).thenReturn(true, false);
    when(mockRs.getString(1)).thenReturn("{\"name\": \"test-ns\"}");

    doAnswer(
            invocation -> {
              HandleCallback callback = invocation.getArgument(0);
              return callback.withHandle(mockHandle);
            })
        .when(mockJdbi)
        .withHandle(any());

    NamespaceResourceV3 resource = new NamespaceResourceV3(mockJdbi);
    Response response = resource.getNamespace("test-ns");

    assertNotNull(response);
    assertEquals(200, response.getStatus());
  }
}

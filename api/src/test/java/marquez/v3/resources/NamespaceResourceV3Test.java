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
import java.util.Collections;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;
import org.junit.jupiter.api.Test;

public class NamespaceResourceV3Test {

  @Test
  public void testListNamespaces() {
    Jdbi mockJdbi = mock(Jdbi.class);
    Handle mockHandle = mock(Handle.class);
    Query mockQuery = mock(Query.class);

    doAnswer(
            invocation -> {
              HandleCallback callback = invocation.getArgument(0);
              return callback.withHandle(mockHandle);
            })
        .when(mockJdbi)
        .withHandle(any());

    org.jdbi.v3.core.result.ResultIterable mockResultIterable =
        mock(org.jdbi.v3.core.result.ResultIterable.class);
    when(mockHandle.createQuery(anyString())).thenReturn(mockQuery);
    when(mockQuery.bind(anyString(), anyString())).thenReturn(mockQuery);
    when(mockQuery.map(any(org.jdbi.v3.core.mapper.RowMapper.class)))
        .thenReturn(mockResultIterable);
    when(mockResultIterable.list()).thenReturn(Collections.emptyList());

    NamespaceResourceV3 resource = new NamespaceResourceV3(mockJdbi);
    Response response = resource.listNamespaces(10);

    assertNotNull(response);
    assertEquals(200, response.getStatus());
  }
}

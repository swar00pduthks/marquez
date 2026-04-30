/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import marquez.service.ColumnLineageService;
import marquez.service.ServiceFactory;
import marquez.service.exceptions.NodeIdNotFoundException;
import marquez.service.models.Lineage;
import marquez.service.models.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Tag("UnitTests")
class ColumnLineageResourceTest {

  @Mock ServiceFactory serviceFactory;
  @Mock ColumnLineageService columnLineageService;
  ColumnLineageResource resource;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(serviceFactory.getColumnLineageService()).thenReturn(columnLineageService);
    when(serviceFactory.getDatasetService()).thenReturn(mock(marquez.service.DatasetService.class));
    when(serviceFactory.getJobService()).thenReturn(mock(marquez.service.JobService.class));
    when(serviceFactory.getNamespaceService())
        .thenReturn(mock(marquez.service.NamespaceService.class));
    when(serviceFactory.getOpenLineageService())
        .thenReturn(mock(marquez.service.OpenLineageService.class));
    when(serviceFactory.getRunService()).thenReturn(mock(marquez.service.RunService.class));
    when(serviceFactory.getSourceService()).thenReturn(mock(marquez.service.SourceService.class));
    when(serviceFactory.getTagService()).thenReturn(mock(marquez.service.TagService.class));
    when(serviceFactory.getDatasetVersionService())
        .thenReturn(mock(marquez.service.DatasetVersionService.class));
    when(serviceFactory.getDatasetFieldService())
        .thenReturn(mock(marquez.service.DatasetFieldService.class));
    when(serviceFactory.getLineageService()).thenReturn(mock(marquez.service.LineageService.class));
    when(serviceFactory.getSearchService()).thenReturn(mock(marquez.service.SearchService.class));
    when(serviceFactory.getStatsService()).thenReturn(mock(marquez.service.StatsService.class));
    resource = new ColumnLineageResource(serviceFactory);
  }

  @Test
  void testGetLineage_missingNodeId_returns400() {
    Response response = resource.getLineage(null, 20, false);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetLineage_blankNodeId_returns400() {
    Response response = resource.getLineage("   ", 20, false);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetLineage_invalidNodeIdFormat_returns400() {
    // "invalid" does not match any valid NodeId pattern
    Response response = resource.getLineage("invalid-format", 20, false);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetLineage_nodeNotFound_returns404() throws Exception {
    when(columnLineageService.lineage(
            org.mockito.ArgumentMatchers.any(NodeId.class), anyInt(), anyBoolean()))
        .thenThrow(new NodeIdNotFoundException("dataset:test-ns:missing-ds"));
    Response response = resource.getLineage("dataset:test-ns:missing-ds", 20, false);
    assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetLineage_validDatasetNode_returns200() throws Exception {
    Lineage lineage = mock(Lineage.class);
    when(columnLineageService.lineage(
            org.mockito.ArgumentMatchers.any(NodeId.class), anyInt(), anyBoolean()))
        .thenReturn(lineage);
    Response response = resource.getLineage("dataset:test-ns:my-ds", 20, false);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    assertEquals(lineage, response.getEntity());
  }

  @Test
  void testGetLineage_withDownstreamTrue_returns200() throws Exception {
    Lineage lineage = mock(Lineage.class);
    when(columnLineageService.lineage(
            org.mockito.ArgumentMatchers.any(NodeId.class), anyInt(), anyBoolean()))
        .thenReturn(lineage);
    Response response = resource.getLineage("dataset:test-ns:my-ds", 5, true);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }
}

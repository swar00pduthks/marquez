/*
 * Copyright 2018-2026 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import marquez.api.exceptions.JobNotFoundException;
import marquez.common.models.JobName;
import marquez.common.models.NamespaceName;
import marquez.service.DatasetService;
import marquez.service.LineageService;
import marquez.service.ServiceFactory;
import marquez.service.models.Lineage;
import marquez.service.models.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class LineageResourceTest {
  @Mock ServiceFactory serviceFactory;
  @Mock LineageService lineageService;
  @Mock DatasetService datasetService;
  @Mock marquez.service.JobService jobService;
  @Mock marquez.service.NamespaceService namespaceService;
  @Mock marquez.service.OpenLineageService openLineageService;
  @Mock marquez.service.RunService runService;
  @Mock marquez.service.SourceService sourceService;
  @Mock marquez.service.TagService tagService;
  @Mock marquez.service.DatasetVersionService datasetVersionService;
  @Mock marquez.service.DatasetFieldService datasetFieldService;
  @Mock marquez.service.ColumnLineageService columnLineageService;
  LineageResource resource;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(serviceFactory.getLineageService()).thenReturn(lineageService);
    when(serviceFactory.getDatasetService()).thenReturn(datasetService);
    when(serviceFactory.getJobService()).thenReturn(jobService);
    when(serviceFactory.getNamespaceService()).thenReturn(namespaceService);
    when(serviceFactory.getOpenLineageService()).thenReturn(openLineageService);
    when(serviceFactory.getRunService()).thenReturn(runService);
    when(serviceFactory.getSourceService()).thenReturn(sourceService);
    when(serviceFactory.getTagService()).thenReturn(tagService);
    when(serviceFactory.getDatasetVersionService()).thenReturn(datasetVersionService);
    when(serviceFactory.getDatasetFieldService()).thenReturn(datasetFieldService);
    when(serviceFactory.getColumnLineageService()).thenReturn(columnLineageService);
    resource = new LineageResource(serviceFactory);
  }

  @Test
  void testGetLineageReturnsOk() {
    NodeId nodeId = NodeId.of(new NamespaceName("ns"), new JobName("job"));
    Lineage lineage = mock(Lineage.class);

    when(jobService.exists(eq("ns"), eq("job"))).thenReturn(true);
    when(lineageService.lineageV2(eq(nodeId), eq(10), eq(false), isNull())).thenReturn(lineage);

    Response response = resource.getLineage(nodeId, 10, false, null);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetLineageThrowsWhenJobMissing() {
    NodeId nodeId = NodeId.of(new NamespaceName("ns"), new JobName("missing-job"));

    when(jobService.exists(eq("ns"), eq("missing-job"))).thenReturn(false);

    assertThrows(JobNotFoundException.class, () -> resource.getLineage(nodeId, 10, false, null));
  }
}

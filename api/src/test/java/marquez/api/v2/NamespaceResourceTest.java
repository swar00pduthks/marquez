/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.Optional;
import marquez.api.exceptions.NamespaceNotFoundException;
import marquez.common.models.NamespaceName;
import marquez.service.DatasetService;
import marquez.service.JobService;
import marquez.service.NamespaceService;
import marquez.service.ServiceFactory;
import marquez.service.models.Namespace;
import marquez.service.models.NamespaceMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Tag("UnitTests")
class NamespaceResourceTest {

  @Mock ServiceFactory serviceFactory;
  @Mock NamespaceService namespaceService;
  @Mock DatasetService datasetService;
  @Mock JobService jobService;
  NamespaceResource resource;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(serviceFactory.getNamespaceService()).thenReturn(namespaceService);
    when(serviceFactory.getDatasetService()).thenReturn(datasetService);
    when(serviceFactory.getJobService()).thenReturn(jobService);
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
    when(serviceFactory.getColumnLineageService())
        .thenReturn(mock(marquez.service.ColumnLineageService.class));
    when(serviceFactory.getSearchService()).thenReturn(mock(marquez.service.SearchService.class));
    when(serviceFactory.getStatsService()).thenReturn(mock(marquez.service.StatsService.class));
    resource = new NamespaceResource(serviceFactory);
  }

  @Test
  void testCreateOrUpdate_returns200() {
    NamespaceName name = NamespaceName.of("my-ns");
    NamespaceMeta meta = mock(NamespaceMeta.class);
    Namespace ns = mock(Namespace.class);
    when(namespaceService.createOrUpdate(eq(name), eq(meta))).thenReturn(ns);
    Response response = resource.createOrUpdate(name, meta);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    assertEquals(ns, response.getEntity());
  }

  @Test
  void testGet_found_returns200() {
    NamespaceName name = NamespaceName.of("my-ns");
    Namespace ns = mock(Namespace.class);
    when(namespaceService.findBy(eq("my-ns"))).thenReturn(Optional.of(ns));
    Response response = resource.get(name);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    assertEquals(ns, response.getEntity());
  }

  @Test
  void testGet_notFound_throws404() {
    NamespaceName name = NamespaceName.of("missing-ns");
    when(namespaceService.findBy(eq("missing-ns"))).thenReturn(Optional.empty());
    assertThrows(NamespaceNotFoundException.class, () -> resource.get(name));
  }

  @Test
  void testList_returns200() {
    when(namespaceService.findAll(anyInt(), anyInt())).thenReturn(Collections.emptyList());
    Response response = resource.list(100, 0);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testDelete_found_returns200AndCascades() {
    NamespaceName name = NamespaceName.of("del-ns");
    Namespace ns = mock(Namespace.class);
    when(ns.getName()).thenReturn(name);
    when(namespaceService.findBy(eq("del-ns"))).thenReturn(Optional.of(ns));
    Response response = resource.delete(name);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    verify(datasetService).deleteByNamespaceName("del-ns");
    verify(jobService).deleteByNamespaceName("del-ns");
    verify(namespaceService).delete("del-ns");
  }

  @Test
  void testDelete_notFound_throws404() {
    NamespaceName name = NamespaceName.of("missing-ns");
    when(namespaceService.findBy(eq("missing-ns"))).thenReturn(Optional.empty());
    assertThrows(NamespaceNotFoundException.class, () -> resource.delete(name));
  }
}

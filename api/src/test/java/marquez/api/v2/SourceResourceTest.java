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
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.Optional;
import marquez.api.exceptions.SourceNotFoundException;
import marquez.common.models.SourceName;
import marquez.service.ServiceFactory;
import marquez.service.SourceService;
import marquez.service.models.Source;
import marquez.service.models.SourceMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Tag("UnitTests")
class SourceResourceTest {

  @Mock ServiceFactory serviceFactory;
  @Mock SourceService sourceService;
  SourceResource resource;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(serviceFactory.getSourceService()).thenReturn(sourceService);
    when(serviceFactory.getDatasetService()).thenReturn(mock(marquez.service.DatasetService.class));
    when(serviceFactory.getJobService()).thenReturn(mock(marquez.service.JobService.class));
    when(serviceFactory.getNamespaceService())
        .thenReturn(mock(marquez.service.NamespaceService.class));
    when(serviceFactory.getOpenLineageService())
        .thenReturn(mock(marquez.service.OpenLineageService.class));
    when(serviceFactory.getRunService()).thenReturn(mock(marquez.service.RunService.class));
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
    resource = new SourceResource(serviceFactory);
  }

  @Test
  void testCreateOrUpdate_returns200() {
    SourceName name = SourceName.of("my-source");
    SourceMeta meta = mock(SourceMeta.class);
    Source source = mock(Source.class);
    when(sourceService.createOrUpdate(eq(name), eq(meta))).thenReturn(source);
    Response response = resource.createOrUpdate(name, meta);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    assertEquals(source, response.getEntity());
  }

  @Test
  void testGet_found_returns200() {
    SourceName name = SourceName.of("my-source");
    Source source = mock(Source.class);
    when(sourceService.findBy(eq("my-source"))).thenReturn(Optional.of(source));
    Response response = resource.get(name);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    assertEquals(source, response.getEntity());
  }

  @Test
  void testGet_notFound_throws404() {
    SourceName name = SourceName.of("missing-source");
    when(sourceService.findBy(eq("missing-source"))).thenReturn(Optional.empty());
    assertThrows(SourceNotFoundException.class, () -> resource.get(name));
  }

  @Test
  void testList_returns200() {
    when(sourceService.findAll(anyInt(), anyInt())).thenReturn(Collections.emptyList());
    Response response = resource.list(100, 0);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testList_wrapsInSourcesKey() {
    when(sourceService.findAll(anyInt(), anyInt())).thenReturn(Collections.emptyList());
    Response response = resource.list(100, 0);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    // Entity is the Sources wrapper, not a raw list
    Object entity = response.getEntity();
    assertEquals("Sources", entity.getClass().getSimpleName());
  }
}

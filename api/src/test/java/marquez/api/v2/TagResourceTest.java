/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import marquez.service.ServiceFactory;
import marquez.service.TagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@org.junit.jupiter.api.Tag("UnitTests")
class TagResourceTest {

  @Mock ServiceFactory serviceFactory;
  @Mock TagService tagService;
  TagResource resource;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(serviceFactory.getTagService()).thenReturn(tagService);
    when(serviceFactory.getDatasetService()).thenReturn(mock(marquez.service.DatasetService.class));
    when(serviceFactory.getJobService()).thenReturn(mock(marquez.service.JobService.class));
    when(serviceFactory.getNamespaceService())
        .thenReturn(mock(marquez.service.NamespaceService.class));
    when(serviceFactory.getOpenLineageService())
        .thenReturn(mock(marquez.service.OpenLineageService.class));
    when(serviceFactory.getRunService()).thenReturn(mock(marquez.service.RunService.class));
    when(serviceFactory.getSourceService()).thenReturn(mock(marquez.service.SourceService.class));
    when(serviceFactory.getDatasetVersionService())
        .thenReturn(mock(marquez.service.DatasetVersionService.class));
    when(serviceFactory.getDatasetFieldService())
        .thenReturn(mock(marquez.service.DatasetFieldService.class));
    when(serviceFactory.getLineageService()).thenReturn(mock(marquez.service.LineageService.class));
    when(serviceFactory.getColumnLineageService())
        .thenReturn(mock(marquez.service.ColumnLineageService.class));
    when(serviceFactory.getSearchService()).thenReturn(mock(marquez.service.SearchService.class));
    when(serviceFactory.getStatsService()).thenReturn(mock(marquez.service.StatsService.class));
    resource = new TagResource(serviceFactory);
  }

  @Test
  void testList_returns200() {
    when(tagService.findAll(anyInt(), anyInt())).thenReturn(Collections.emptySet());
    Response response = resource.list(100, 0);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testList_wrapsInTagsKey() {
    when(tagService.findAll(anyInt(), anyInt())).thenReturn(Collections.emptySet());
    Response response = resource.list(100, 0);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    Object entity = response.getEntity();
    assertEquals("Tags", entity.getClass().getSimpleName());
  }

  @Test
  void testCreate_returns200() {
    marquez.service.models.Tag tag = mock(marquez.service.models.Tag.class);
    TagResource.TagDescription description = new TagResource.TagDescription("test description");
    when(tagService.upsert(org.mockito.ArgumentMatchers.any(marquez.service.models.Tag.class)))
        .thenReturn(tag);
    Response response = resource.create("my-tag", description);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    assertEquals(tag, response.getEntity());
  }

  @Test
  void testCreate_noDescription_returns200() {
    marquez.service.models.Tag tag = mock(marquez.service.models.Tag.class);
    TagResource.TagDescription description = new TagResource.TagDescription(null);
    when(tagService.upsert(org.mockito.ArgumentMatchers.any(marquez.service.models.Tag.class)))
        .thenReturn(tag);
    Response response = resource.create("no-desc-tag", description);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }
}

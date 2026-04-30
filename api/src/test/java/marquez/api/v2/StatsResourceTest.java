/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import marquez.api.models.Period;
import marquez.service.ServiceFactory;
import marquez.service.StatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Tag("UnitTests")
class StatsResourceTest {

  @Mock ServiceFactory serviceFactory;
  @Mock StatsService statsService;
  StatsResource resource;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(serviceFactory.getStatsService()).thenReturn(statsService);
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
    when(serviceFactory.getColumnLineageService())
        .thenReturn(mock(marquez.service.ColumnLineageService.class));
    when(serviceFactory.getSearchService()).thenReturn(mock(marquez.service.SearchService.class));
    resource = new StatsResource(serviceFactory);
  }

  // ── /stats/lineage-events ──────────────────────────────────────────────────

  @Test
  void testGetStats_dayPeriod_returns200() {
    when(statsService.getLastDayLineageMetrics()).thenReturn(Collections.emptyList());
    Response response = resource.getStats(Period.DAY, null);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetStats_weekPeriodWithTimezone_returns200() {
    when(statsService.getLastWeekLineageMetrics("Europe/London"))
        .thenReturn(Collections.emptyList());
    Response response = resource.getStats(Period.WEEK, "Europe/London");
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetStats_weekPeriodNoTimezone_returns400() {
    Response response = resource.getStats(Period.WEEK, null);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetStats_weekPeriodEmptyTimezone_returns400() {
    Response response = resource.getStats(Period.WEEK, "");
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetStats_nullPeriod_returns400() {
    Response response = resource.getStats(null, null);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  // ── /stats/jobs ───────────────────────────────────────────────────────────

  @Test
  void testGetJobs_dayPeriod_returns200() {
    when(statsService.getLastDayJobs()).thenReturn(Collections.emptyList());
    Response response = resource.getJobs(Period.DAY, null);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetJobs_weekPeriod_returns200() {
    when(statsService.getLastWeekJobs("UTC")).thenReturn(Collections.emptyList());
    Response response = resource.getJobs(Period.WEEK, "UTC");
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetJobs_nullPeriod_returns400() {
    Response response = resource.getJobs(null, null);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  // ── /stats/datasets ───────────────────────────────────────────────────────

  @Test
  void testGetDatasets_dayPeriod_returns200() {
    when(statsService.getLastDayDatasets()).thenReturn(Collections.emptyList());
    Response response = resource.getDatasets(Period.DAY, null);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetDatasets_weekPeriod_returns200() {
    when(statsService.getLastWeekDatasets("UTC")).thenReturn(Collections.emptyList());
    Response response = resource.getDatasets(Period.WEEK, "UTC");
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetDatasets_nullPeriod_returns400() {
    Response response = resource.getDatasets(null, null);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  // ── /stats/sources ────────────────────────────────────────────────────────

  @Test
  void testGetSources_dayPeriod_returns200() {
    when(statsService.getLastDaySources()).thenReturn(Collections.emptyList());
    Response response = resource.getSources(Period.DAY, null);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetSources_weekPeriod_returns200() {
    when(statsService.getLastWeekSources("UTC")).thenReturn(Collections.emptyList());
    Response response = resource.getSources(Period.WEEK, "UTC");
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetSources_nullPeriod_returns400() {
    Response response = resource.getSources(null, null);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }
}

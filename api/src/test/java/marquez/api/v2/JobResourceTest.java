/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import marquez.db.models.JobRow;
import marquez.service.JobService;
import marquez.service.ServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class JobResourceTest {
  @Mock ServiceFactory serviceFactory;
  @Mock JobService jobService;
  @Mock marquez.service.DatasetService datasetService;
  JobResource resource;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(serviceFactory.getJobService()).thenReturn(jobService);
    when(serviceFactory.getDatasetService()).thenReturn(datasetService);
    resource = new JobResource(serviceFactory);
  }

  @Test
  void testListJobs_returnsOk() {
    java.util.UUID nsUuid = java.util.UUID.randomUUID();
    List<JobRow> jobs = Collections.emptyList();
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(jobService.findAllJobsV2(eq(nsUuid), anyInt(), anyInt())).thenReturn(jobs);
    Response response = resource.listJobs("testns", 100, 0);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetJob_found() {
    java.util.UUID nsUuid = java.util.UUID.randomUUID();
    JobRow row = mock(JobRow.class);
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(jobService.findJobByNameV2(eq(nsUuid), eq("job"))).thenReturn(Optional.of(row));
    Response response = resource.getJob("testns", "job");
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetJob_notFound() {
    java.util.UUID nsUuid = java.util.UUID.randomUUID();
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(jobService.findJobByNameV2(eq(nsUuid), eq("job"))).thenReturn(Optional.empty());
    Response response = resource.getJob("testns", "job");
    assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
  }
}

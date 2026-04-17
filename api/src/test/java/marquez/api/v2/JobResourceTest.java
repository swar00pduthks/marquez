/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import marquez.service.JobService;
import marquez.service.ServiceFactory;
import marquez.service.models.Job;
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
    List<Job> jobs = Collections.emptyList();
    when(jobService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(jobService.findAllJobsV2(eq(nsUuid), anyInt(), anyInt(), anySet())).thenReturn(jobs);
    Response response = resource.listJobs("testns", 100, 0, Collections.emptySet());
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetJob_found() {
    java.util.UUID nsUuid = java.util.UUID.randomUUID();
    Job job = mock(Job.class);
    when(jobService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(jobService.findJobByNameV2(eq(nsUuid), eq("job"), anySet())).thenReturn(Optional.of(job));
    Response response = resource.getJob("testns", "job", Collections.emptySet());
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetJob_notFound() {
    java.util.UUID nsUuid = java.util.UUID.randomUUID();
    when(jobService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(jobService.findJobByNameV2(eq(nsUuid), eq("job"), anySet())).thenReturn(Optional.empty());
    Response response = resource.getJob("testns", "job", Collections.emptySet());
    assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
  }
}

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
import java.util.UUID;
import marquez.common.models.JobName;
import marquez.common.models.NamespaceName;
import marquez.db.JobFacetsDao;
import marquez.db.JobVersionDao;
import marquez.db.RunFacetsDao;
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
  @Mock JobVersionDao jobVersionDao;
  @Mock JobFacetsDao jobFacetsDao;
  @Mock RunFacetsDao runFacetsDao;
  JobResource resource;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(serviceFactory.getJobService()).thenReturn(jobService);
    when(serviceFactory.getDatasetService()).thenReturn(datasetService);
    resource = new JobResource(serviceFactory, jobVersionDao, jobFacetsDao, runFacetsDao);
  }

  @Test
  void testListJobs_returnsOk() {
    UUID nsUuid = UUID.randomUUID();
    List<Job> jobs = Collections.emptyList();
    NamespaceName ns = NamespaceName.of("testns");
    when(jobService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(jobService.findAllJobsV2(eq(nsUuid), anyInt(), anyInt(), anySet())).thenReturn(jobs);
    when(jobService.countFor(eq("testns"))).thenReturn(0);
    Response response = resource.listJobs(ns, 100, 0, Collections.emptySet());
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetJob_found() {
    UUID nsUuid = UUID.randomUUID();
    Job job = mock(Job.class);
    NamespaceName ns = NamespaceName.of("testns");
    JobName jobName = JobName.of("job");
    when(jobService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(jobService.findJobByNameV2(eq(nsUuid), eq("job"), anySet())).thenReturn(Optional.of(job));
    Response response = resource.getJob(ns, jobName, Collections.emptySet());
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetJob_notFound() {
    UUID nsUuid = UUID.randomUUID();
    NamespaceName ns = NamespaceName.of("testns");
    JobName jobName = JobName.of("job");
    when(jobService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(jobService.findJobByNameV2(eq(nsUuid), eq("job"), anySet())).thenReturn(Optional.empty());
    // Should throw JobNotFoundException (404)
    org.junit.jupiter.api.Assertions.assertThrows(
        marquez.api.exceptions.JobNotFoundException.class,
        () -> resource.getJob(ns, jobName, Collections.emptySet()));
  }
}

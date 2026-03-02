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
import marquez.service.DatasetVersionService;
import marquez.service.ServiceFactory;
import marquez.service.models.DatasetVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class DatasetVersionResourceTest {
  @Mock ServiceFactory serviceFactory;
  @Mock DatasetVersionService datasetVersionService;
  @Mock marquez.service.DatasetService datasetService;
  DatasetVersionResource resource;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(serviceFactory.getDatasetVersionService()).thenReturn(datasetVersionService);
    when(serviceFactory.getDatasetService()).thenReturn(datasetService);
    resource = new DatasetVersionResource(serviceFactory);
  }

  @Test
  void testListDatasetVersions_returnsOk() {
    java.util.UUID nsUuid = java.util.UUID.randomUUID();
    java.util.UUID dsUuid = java.util.UUID.randomUUID();
    List<DatasetVersion> versions = Collections.emptyList();
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(datasetService.findDatasetUuidByName(eq(nsUuid), eq("ds")))
        .thenReturn(Optional.of(dsUuid));
    when(datasetVersionService.findAllDatasetVersionsV2(eq(dsUuid), anyInt(), anyInt(), anySet()))
        .thenReturn(versions);
    Response response =
        resource.listDatasetVersions("testns", "ds", 100, 0, Collections.emptySet());
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetDatasetVersion_found() {
    java.util.UUID nsUuid = java.util.UUID.randomUUID();
    java.util.UUID dsUuid = java.util.UUID.randomUUID();
    DatasetVersion dv = mock(DatasetVersion.class);
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(datasetService.findDatasetUuidByName(eq(nsUuid), eq("ds")))
        .thenReturn(Optional.of(dsUuid));
    when(datasetVersionService.findDatasetVersionByVersionV2(eq(dsUuid), eq("ver"), anySet()))
        .thenReturn(Optional.of(dv));
    Response response = resource.getDatasetVersion("testns", "ds", "ver", Collections.emptySet());
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetDatasetVersion_notFound() {
    java.util.UUID nsUuid = java.util.UUID.randomUUID();
    java.util.UUID dsUuid = java.util.UUID.randomUUID();
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(datasetService.findDatasetUuidByName(eq(nsUuid), eq("ds")))
        .thenReturn(Optional.of(dsUuid));
    when(datasetVersionService.findDatasetVersionByVersionV2(eq(dsUuid), eq("ver"), anySet()))
        .thenReturn(Optional.empty());
    Response response = resource.getDatasetVersion("testns", "ds", "ver", Collections.emptySet());
    assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
  }
}

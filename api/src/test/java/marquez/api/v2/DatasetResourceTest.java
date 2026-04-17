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
import marquez.service.DatasetService;
import marquez.service.ServiceFactory;
import marquez.service.models.Dataset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class DatasetResourceTest {
  @Mock ServiceFactory serviceFactory;
  @Mock DatasetService datasetService;
  DatasetResource resource;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(serviceFactory.getDatasetService()).thenReturn(datasetService);
    resource = new DatasetResource(serviceFactory);
  }

  @Test
  void testListDatasets_returnsOk() {
    java.util.UUID nsUuid = java.util.UUID.randomUUID();
    List<Dataset> datasets = Collections.emptyList();
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(datasetService.findAllDatasetsV2(eq(nsUuid), anyInt(), anyInt(), anySet()))
        .thenReturn(datasets);
    Response response = resource.listDatasets("testns", 100, 0, Collections.emptySet());
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetDataset_found() {
    java.util.UUID nsUuid = java.util.UUID.randomUUID();
    Dataset ds = mock(Dataset.class);
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(datasetService.findDatasetByNameV2(eq(nsUuid), eq("ds"), anySet()))
        .thenReturn(Optional.of(ds));
    Response response = resource.getDataset("testns", "ds", Collections.emptySet());
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetDataset_notFound() {
    java.util.UUID nsUuid = java.util.UUID.randomUUID();
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(datasetService.findDatasetByNameV2(eq(nsUuid), eq("ds"), anySet()))
        .thenReturn(Optional.empty());
    Response response = resource.getDataset("testns", "ds", Collections.emptySet());
    assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
  }
}

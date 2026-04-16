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
import marquez.common.models.DatasetName;
import marquez.common.models.NamespaceName;
import marquez.service.ColumnLineageService;
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
  @Mock ColumnLineageService columnLineageService;
  DatasetResource resource;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(serviceFactory.getDatasetService()).thenReturn(datasetService);
    when(serviceFactory.getColumnLineageService()).thenReturn(columnLineageService);
    resource = new DatasetResource(serviceFactory);
  }

  @Test
  void testListDatasets_returnsOk() {
    UUID nsUuid = UUID.randomUUID();
    List<Dataset> datasets = Collections.emptyList();
    NamespaceName ns = NamespaceName.of("testns");
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(datasetService.findAllDatasetsV2(eq(nsUuid), anyInt(), anyInt(), anySet()))
        .thenReturn(datasets);
    when(datasetService.countDatasets(eq("testns"))).thenReturn(0);
    Response response = resource.list(ns, 100, 0, Collections.emptySet());
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetDataset_found() {
    UUID nsUuid = UUID.randomUUID();
    Dataset ds = mock(Dataset.class);
    NamespaceName ns = NamespaceName.of("testns");
    DatasetName dsName = DatasetName.of("ds");
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(datasetService.findDatasetByNameV2(eq(nsUuid), eq("ds"), anySet()))
        .thenReturn(Optional.of(ds));
    Response response = resource.getDataset(ns, dsName, Collections.emptySet());
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetDataset_notFound() {
    UUID nsUuid = UUID.randomUUID();
    NamespaceName ns = NamespaceName.of("testns");
    DatasetName dsName = DatasetName.of("ds");
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(datasetService.findDatasetByNameV2(eq(nsUuid), eq("ds"), anySet()))
        .thenReturn(Optional.empty());
    // Should throw DatasetNotFoundException (which is a NotFoundException → 404)
    org.junit.jupiter.api.Assertions.assertThrows(
        marquez.api.exceptions.DatasetNotFoundException.class,
        () -> resource.getDataset(ns, dsName, Collections.emptySet()));
  }
}

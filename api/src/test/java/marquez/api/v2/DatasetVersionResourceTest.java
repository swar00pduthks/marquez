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
    UUID nsUuid = UUID.randomUUID();
    UUID dsUuid = UUID.randomUUID();
    List<DatasetVersion> versions = Collections.emptyList();
    NamespaceName ns = NamespaceName.of("testns");
    DatasetName dsName = DatasetName.of("ds");
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(datasetService.findDatasetUuidByName(eq(nsUuid), eq("ds")))
        .thenReturn(Optional.of(dsUuid));
    when(datasetVersionService.findAllDatasetVersionsV2(eq(dsUuid), anyInt(), anyInt(), anySet()))
        .thenReturn(versions);
    Response response = resource.listDatasetVersions(ns, dsName, 100, 0, Collections.emptySet());
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetDatasetVersion_found() {
    UUID nsUuid = UUID.randomUUID();
    UUID dsUuid = UUID.randomUUID();
    String ver = UUID.randomUUID().toString();
    DatasetVersion dv = mock(DatasetVersion.class);
    NamespaceName ns = NamespaceName.of("testns");
    DatasetName dsName = DatasetName.of("ds");
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(datasetService.findDatasetUuidByName(eq(nsUuid), eq("ds")))
        .thenReturn(Optional.of(dsUuid));
    when(datasetVersionService.findDatasetVersionByVersionV2(eq(dsUuid), eq(ver), anySet()))
        .thenReturn(Optional.of(dv));
    Response response = resource.getDatasetVersion(ns, dsName, ver, Collections.emptySet());
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  void testGetDatasetVersion_notFound() {
    UUID nsUuid = UUID.randomUUID();
    UUID dsUuid = UUID.randomUUID();
    String ver = UUID.randomUUID().toString();
    NamespaceName ns = NamespaceName.of("testns");
    DatasetName dsName = DatasetName.of("ds");
    when(datasetService.findNamespaceUuidByName(eq("testns"))).thenReturn(Optional.of(nsUuid));
    when(datasetService.findDatasetUuidByName(eq(nsUuid), eq("ds")))
        .thenReturn(Optional.of(dsUuid));
    when(datasetVersionService.findDatasetVersionByVersionV2(eq(dsUuid), eq(ver), anySet()))
        .thenReturn(Optional.empty());
    // Should throw NotFoundException (404)
    org.junit.jupiter.api.Assertions.assertThrows(
        jakarta.ws.rs.NotFoundException.class,
        () -> resource.getDatasetVersion(ns, dsName, ver, Collections.emptySet()));
  }
}

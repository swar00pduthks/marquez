/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.client;

import static java.time.temporal.ChronoUnit.MILLIS;
import static marquez.client.MarquezClient.DEFAULT_BASE_URL;
import static marquez.client.MarquezPathV1.BASE_PATH;
import static marquez.client.models.ModelGenerator.newConnectionUrl;
import static marquez.client.models.ModelGenerator.newDatasetFacets;
import static marquez.client.models.ModelGenerator.newDatasetIdWith;
import static marquez.client.models.ModelGenerator.newDatasetPhysicalName;
import static marquez.client.models.ModelGenerator.newDescription;
import static marquez.client.models.ModelGenerator.newFields;
import static marquez.client.models.ModelGenerator.newInputDatasetVersion;
import static marquez.client.models.ModelGenerator.newInputs;
import static marquez.client.models.ModelGenerator.newJobIdWith;
import static marquez.client.models.ModelGenerator.newJobType;
import static marquez.client.models.ModelGenerator.newLocation;
import static marquez.client.models.ModelGenerator.newNamespaceName;
import static marquez.client.models.ModelGenerator.newOutputDatasetVersion;
import static marquez.client.models.ModelGenerator.newOutputs;
import static marquez.client.models.ModelGenerator.newOwnerName;
import static marquez.client.models.ModelGenerator.newRunArgs;
import static marquez.client.models.ModelGenerator.newRunId;
import static marquez.client.models.ModelGenerator.newSchemaLocation;
import static marquez.client.models.ModelGenerator.newSourceName;
import static marquez.client.models.ModelGenerator.newSourceType;
import static marquez.client.models.ModelGenerator.newStreamName;
import static marquez.client.models.ModelGenerator.newTagNames;
import static marquez.client.models.ModelGenerator.newTimestamp;
import static marquez.client.models.ModelGenerator.newVersion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.net.URI;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import lombok.NonNull;
import lombok.Value;
import marquez.client.MarquezClient.DatasetVersions;
import marquez.client.MarquezClient.Datasets;
import marquez.client.MarquezClient.Events;
import marquez.client.MarquezClient.Jobs;
import marquez.client.MarquezClient.Namespaces;
import marquez.client.MarquezClient.Runs;
import marquez.client.MarquezClient.Sources;
import marquez.client.MarquezClient.Tags;
import marquez.client.models.ColumnLineageInputField;
import marquez.client.models.ColumnLineageNodeData;
import marquez.client.models.Dataset;
import marquez.client.models.DatasetFieldId;
import marquez.client.models.DatasetId;
import marquez.client.models.DatasetNodeData;
import marquez.client.models.DatasetType;
import marquez.client.models.DatasetVersion;
import marquez.client.models.DbTable;
import marquez.client.models.DbTableMeta;
import marquez.client.models.DbTableVersion;
import marquez.client.models.Edge;
import marquez.client.models.Field;
import marquez.client.models.InputDatasetVersion;
import marquez.client.models.Job;
import marquez.client.models.JobId;
import marquez.client.models.JobMeta;
import marquez.client.models.JobType;
import marquez.client.models.JobVersionId;
import marquez.client.models.JsonGenerator;
import marquez.client.models.LineageEvent;
import marquez.client.models.Namespace;
import marquez.client.models.NamespaceMeta;
import marquez.client.models.Node;
import marquez.client.models.NodeId;
import marquez.client.models.NodeType;
import marquez.client.models.OutputDatasetVersion;
import marquez.client.models.Run;
import marquez.client.models.RunMeta;
import marquez.client.models.RunState;
import marquez.client.models.Source;
import marquez.client.models.SourceMeta;
import marquez.client.models.Stream;
import marquez.client.models.StreamMeta;
import marquez.client.models.StreamVersion;
import marquez.client.models.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@org.junit.jupiter.api.Tag("UnitTests")
@ExtendWith(MockitoExtension.class)
public class MarquezClientTest {
  // COMMON
  private static final Instant CREATED_AT = newTimestamp();
  private static final Instant UPDATED_AT = CREATED_AT;
  private static final Instant LAST_MODIFIED_AT = newTimestamp();
  private static final String VERSION = newVersion();
  private static final UUID CURRENT_VERSION = UUID.fromString(VERSION);
  // NAMESPACE
  private static final String NAMESPACE_NAME = newNamespaceName();
  private static final String OWNER_NAME = newOwnerName();
  private static final String NAMESPACE_DESCRIPTION = newDescription();
  private static final Namespace NAMESPACE =
      new Namespace(
          NAMESPACE_NAME, CREATED_AT, UPDATED_AT, OWNER_NAME, NAMESPACE_DESCRIPTION, false);

  // SOURCE
  private static final String SOURCE_TYPE = newSourceType();
  private static final String SOURCE_NAME = newSourceName();
  private static final URI CONNECTION_URL = newConnectionUrl();
  private static final String SOURCE_DESCRIPTION = newDescription();
  private static final Source SOURCE =
      new Source(
          SOURCE_TYPE, SOURCE_NAME, CREATED_AT, UPDATED_AT, CONNECTION_URL, SOURCE_DESCRIPTION);

  // DB TABLE DATASET
  private static final DatasetId DB_TABLE_ID = newDatasetIdWith(NAMESPACE_NAME);
  private static final String DB_TABLE_NAME = DB_TABLE_ID.getName();
  private static final String DB_TABLE_PHYSICAL_NAME = newDatasetPhysicalName();
  private static final String DB_TABLE_SOURCE_NAME = newSourceName();
  private static final String DB_TABLE_DESCRIPTION = newDescription();
  private static final List<Field> FIELDS = newFields(4);
  private static final Set<String> TAGS = newTagNames(4);
  private static final Map<String, Object> DB_FACETS = newDatasetFacets(4);
  private static final String FIELD_NAME = "test_field";

  private static final DbTable DB_TABLE =
      new DbTable(
          DB_TABLE_ID,
          DB_TABLE_NAME,
          DB_TABLE_PHYSICAL_NAME,
          CREATED_AT,
          UPDATED_AT,
          NAMESPACE_NAME,
          DB_TABLE_SOURCE_NAME,
          FIELDS,
          TAGS,
          null,
          DB_TABLE_DESCRIPTION,
          null,
          DB_FACETS,
          CURRENT_VERSION);
  private static final DbTable DB_TABLE_MODIFIED =
      new DbTable(
          DB_TABLE_ID,
          DB_TABLE_NAME,
          DB_TABLE_PHYSICAL_NAME,
          CREATED_AT,
          UPDATED_AT,
          NAMESPACE_NAME,
          DB_TABLE_SOURCE_NAME,
          FIELDS,
          TAGS,
          LAST_MODIFIED_AT,
          DB_TABLE_DESCRIPTION,
          null,
          DB_FACETS,
          CURRENT_VERSION);

  // RAW LINEAGE EVENT

  private static final LineageEvent RAW_LINEAGE_EVENT =
      new LineageEvent(
          "START",
          ZonedDateTime.now(ZoneId.of("Z")),
          Collections.emptyMap(),
          Collections.emptyMap(),
          Collections.emptyList(),
          Collections.emptyList(),
          URI.create("http://localhost:8080"),
          URI.create("https://openlineage.io/spec/2-0-0/OpenLineage.json#/definitions/RunEvent"));

  // STREAM DATASET
  private static final DatasetId STREAM_ID = newDatasetIdWith(NAMESPACE_NAME);
  private static final String STREAM_NAME = STREAM_ID.getName();
  private static final String STREAM_PHYSICAL_NAME = newStreamName();
  private static final String STREAM_SOURCE_NAME = newSourceName();
  private static final URL STREAM_SCHEMA_LOCATION = newSchemaLocation();
  private static final String STREAM_DESCRIPTION = newDescription();
  private static final Stream STREAM =
      new Stream(
          STREAM_ID,
          STREAM_NAME,
          STREAM_PHYSICAL_NAME,
          CREATED_AT,
          UPDATED_AT,
          NAMESPACE_NAME,
          STREAM_SOURCE_NAME,
          FIELDS,
          TAGS,
          null,
          STREAM_SCHEMA_LOCATION,
          STREAM_DESCRIPTION,
          null,
          DB_FACETS,
          CURRENT_VERSION);
  private static final Stream STREAM_MODIFIED =
      new Stream(
          STREAM_ID,
          STREAM_NAME,
          STREAM_PHYSICAL_NAME,
          CREATED_AT,
          UPDATED_AT,
          NAMESPACE_NAME,
          STREAM_SOURCE_NAME,
          FIELDS,
          TAGS,
          LAST_MODIFIED_AT,
          STREAM_SCHEMA_LOCATION,
          STREAM_DESCRIPTION,
          null,
          DB_FACETS,
          CURRENT_VERSION);

  // JOB
  private static final JobId JOB_ID = newJobIdWith(NAMESPACE_NAME);
  private static final String JOB_NAME = JOB_ID.getName();
  private static final Set<DatasetId> INPUTS = newInputs(2);
  private static final Set<DatasetId> OUTPUTS = newOutputs(4);
  private static final URL LOCATION = newLocation();
  private static final JobType JOB_TYPE = newJobType();
  private static final String JOB_DESCRIPTION = newDescription();
  private static final JobVersionId JOB_VERSION =
      new JobVersionId(JOB_ID.getNamespace(), JOB_ID.getName(), CURRENT_VERSION);
  private static final Job JOB =
      new Job(
          JOB_ID,
          JOB_TYPE,
          JOB_NAME,
          JOB_NAME,
          null,
          CREATED_AT,
          UPDATED_AT,
          NAMESPACE_NAME,
          INPUTS,
          OUTPUTS,
          LOCATION,
          JOB_DESCRIPTION,
          null,
          null,
          null,
          null);

  // RUN
  private static final Instant NOMINAL_START_TIME = newTimestamp();
  private static final Instant NOMINAL_END_TIME = newTimestamp();
  private static final Instant START_AT = newTimestamp();
  private static final Instant ENDED_AT = START_AT.plusMillis(1000L);
  private static final long DURATION = START_AT.until(ENDED_AT, MILLIS);
  private static final Map<String, String> RUN_ARGS = newRunArgs();

  private static final List<InputDatasetVersion> INPUT_RUN_DATASET_FACETS =
      Collections.singletonList(newInputDatasetVersion());

  private static final List<OutputDatasetVersion> OUTPUT_RUN_DATASET_FACETS =
      Collections.singletonList(newOutputDatasetVersion());

  private static final Run NEW =
      new Run(
          newRunId(),
          CREATED_AT,
          UPDATED_AT,
          NOMINAL_START_TIME,
          NOMINAL_END_TIME,
          RunState.NEW,
          START_AT,
          ENDED_AT,
          DURATION,
          RUN_ARGS,
          JOB_VERSION,
          null,
          INPUT_RUN_DATASET_FACETS,
          OUTPUT_RUN_DATASET_FACETS);
  private static final Run RUNNING =
      new Run(
          newRunId(),
          CREATED_AT,
          UPDATED_AT,
          NOMINAL_START_TIME,
          NOMINAL_END_TIME,
          RunState.RUNNING,
          START_AT,
          ENDED_AT,
          DURATION,
          RUN_ARGS,
          JOB_VERSION,
          null,
          INPUT_RUN_DATASET_FACETS,
          OUTPUT_RUN_DATASET_FACETS);
  private static final Run COMPLETED =
      new Run(
          newRunId(),
          CREATED_AT,
          UPDATED_AT,
          NOMINAL_START_TIME,
          NOMINAL_END_TIME,
          RunState.COMPLETED,
          START_AT,
          ENDED_AT,
          DURATION,
          RUN_ARGS,
          JOB_VERSION,
          null,
          INPUT_RUN_DATASET_FACETS,
          OUTPUT_RUN_DATASET_FACETS);
  private static final Run ABORTED =
      new Run(
          newRunId(),
          CREATED_AT,
          UPDATED_AT,
          NOMINAL_START_TIME,
          NOMINAL_END_TIME,
          RunState.ABORTED,
          START_AT,
          ENDED_AT,
          DURATION,
          RUN_ARGS,
          JOB_VERSION,
          null,
          INPUT_RUN_DATASET_FACETS,
          OUTPUT_RUN_DATASET_FACETS);
  private static final Run FAILED =
      new Run(
          newRunId(),
          CREATED_AT,
          UPDATED_AT,
          NOMINAL_START_TIME,
          NOMINAL_END_TIME,
          RunState.FAILED,
          START_AT,
          ENDED_AT,
          DURATION,
          RUN_ARGS,
          JOB_VERSION,
          null,
          INPUT_RUN_DATASET_FACETS,
          OUTPUT_RUN_DATASET_FACETS);

  private static final String RUN_ID = newRunId();
  private static final Job JOB_WITH_LATEST_RUN =
      new Job(
          JOB_ID,
          JOB_TYPE,
          JOB_NAME,
          JOB_NAME,
          null,
          CREATED_AT,
          UPDATED_AT,
          NAMESPACE_NAME,
          INPUTS,
          OUTPUTS,
          LOCATION,
          JOB_DESCRIPTION,
          new Run(
              RUN_ID,
              CREATED_AT,
              UPDATED_AT,
              NOMINAL_START_TIME,
              NOMINAL_END_TIME,
              RunState.RUNNING,
              START_AT,
              ENDED_AT,
              DURATION,
              RUN_ARGS,
              JOB_VERSION,
              null,
              INPUT_RUN_DATASET_FACETS,
              OUTPUT_RUN_DATASET_FACETS),
          null,
          null,
          null);

  // DATASET VERSIONS
  private static final Run CREATED_BY_RUN = COMPLETED;
  private static final DbTableVersion DB_TABLE_VERSION =
      new DbTableVersion(
          DB_TABLE_ID,
          DB_TABLE_NAME,
          DB_TABLE_PHYSICAL_NAME,
          CREATED_AT,
          VERSION,
          DB_TABLE_SOURCE_NAME,
          FIELDS,
          TAGS,
          DB_TABLE_DESCRIPTION,
          CREATED_BY_RUN,
          DB_FACETS);
  private static final StreamVersion STREAM_VERSION =
      new StreamVersion(
          STREAM_ID,
          STREAM_NAME,
          STREAM_PHYSICAL_NAME,
          CREATED_AT,
          VERSION,
          STREAM_SOURCE_NAME,
          FIELDS,
          TAGS,
          STREAM_SCHEMA_LOCATION,
          STREAM_DESCRIPTION,
          CREATED_BY_RUN,
          DB_FACETS);

  private static final DatasetId DATASET_ID = new DatasetId(NAMESPACE_NAME, DB_TABLE_NAME);

  private static final DatasetFieldId DATASET_FIELD_ID =
      new DatasetFieldId(NAMESPACE_NAME, DB_TABLE_NAME, FIELD_NAME);

  private static final DatasetFieldId DATASET_FIELD_VERSION_ID =
      new DatasetFieldId(NAMESPACE_NAME, DB_TABLE_NAME, FIELD_NAME);

  private static final Node LINEAGE_NODE =
      new Node(
          NodeId.of(DATASET_ID),
          NodeType.DATASET,
          new DatasetNodeData(
              DATASET_ID,
              DatasetType.DB_TABLE,
              DB_TABLE_NAME,
              DB_TABLE_PHYSICAL_NAME,
              CREATED_AT,
              UPDATED_AT,
              NAMESPACE_NAME,
              DB_TABLE_SOURCE_NAME,
              FIELDS,
              TAGS,
              null,
              DB_TABLE_DESCRIPTION,
              null),
          ImmutableSet.of(
              Edge.of(NodeId.of(DATASET_ID), NodeId.of(new DatasetId("namespace", "inDataset")))),
          ImmutableSet.of(
              Edge.of(NodeId.of(new DatasetId("namespace", "outDataset")), NodeId.of(DATASET_ID))));

  private static final Node COLUMN_LINEAGE_NODE =
      new Node(
          NodeId.of(DATASET_FIELD_ID),
          NodeType.DATASET_FIELD,
          new ColumnLineageNodeData(
              NAMESPACE_NAME,
              DB_TABLE_NAME,
              FIELD_NAME,
              "String",
              Collections.singletonList(
                  new ColumnLineageInputField(
                      "namespace",
                      "inDataset",
                      "some-col1",
                      "transformationDescription",
                      "transformationType"))),
          ImmutableSet.of(
              Edge.of(
                  NodeId.of(DATASET_FIELD_ID),
                  NodeId.of(new DatasetFieldId("namespace", "inDataset", "some-col1")))),
          ImmutableSet.of(
              Edge.of(
                  NodeId.of(new DatasetFieldId("namespace", "outDataset", "some-col2")),
                  NodeId.of(DATASET_FIELD_ID))));

  private static final List<LineageEvent> EVENTS = Collections.singletonList(RAW_LINEAGE_EVENT);
  private static final ZonedDateTime BEFORE_TIMESTAMP = ZonedDateTime.parse("2020-01-01T00:00:00Z");
  private static final ZonedDateTime AFTER_TIMESTAMP = ZonedDateTime.parse("2019-01-01T00:00:00Z");

  private final MarquezUrl marquezUrl = MarquezUrl.create(DEFAULT_BASE_URL);
  @Mock private MarquezHttp http;
  private MarquezClient client;

  @Value
  static class ResultsPage<T> {
    @NonNull Map<String, T> value;

    @JsonProperty("totalCount")
    int totalCount;

    public ResultsPage(String propertyName, T value, int totalCount) {
      this.value = setValue(propertyName, value);
      this.totalCount = totalCount;
    }

    @JsonAnySetter
    public Map<String, T> setValue(String key, T value) {
      return Collections.singletonMap(key, value);
    }

    @JsonAnyGetter
    public @NonNull Map<String, T> getValue() {
      return value;
    }
  }

  @BeforeEach
  public void setUp() {
    client = new MarquezClient(marquezUrl, http);
  }

  @Test
  public void testClientBuilder_default() {
    final MarquezClient client = MarquezClient.builder().build();
    assertThat(client.url.baseUrl).isEqualTo(DEFAULT_BASE_URL);
  }

  @Test
  public void testClientBuilder_overrideUrl() throws Exception {
    final URL url = new URL("http://test.com:8080");
    final MarquezClient client = MarquezClient.builder().baseUrl(url).build();
    assertThat(client.url.baseUrl).isEqualTo(url);
  }

  @Test
  public void testClientBuilder_throwsOnBadUrl() {
    final String badUrlString = "test.com/api/v1";
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> MarquezClient.builder().baseUrl(badUrlString).build());
  }

  @Test
  public void testClientBuilder_sslContext()
      throws NoSuchAlgorithmException, KeyManagementException {
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(new KeyManager[0], new TrustManager[0], null);

    MarquezClient.Builder builder = MarquezClient.builder();
    assertThat(builder.sslContext == null);

    builder.sslContext(sslContext);
    assertThat(builder.sslContext != null);

    builder.build();
  }

  @Test
  public void testClientBuilder_httpCustomizer() {
    MarquezClient.Builder builder = MarquezClient.builder();
    assertThat(builder.httpCustomizer == null);

    builder.customize(httpClientBuilder -> httpClientBuilder.setMaxConnTotal(30));
    assertThat(builder.httpCustomizer != null);

    builder.build();
  }

  @Test
  public void testCreateNamespace() throws Exception {
    final URL url = buildUrlFor("/namespaces/%s", NAMESPACE_NAME);

    final NamespaceMeta meta =
        NamespaceMeta.builder().ownerName(OWNER_NAME).description(NAMESPACE_DESCRIPTION).build();
    final String metaAsJson = JsonGenerator.newJsonFor(meta);
    final String namespaceAsJson = JsonGenerator.newJsonFor(NAMESPACE);
    when(http.put(url, metaAsJson)).thenReturn(namespaceAsJson);

    final Namespace namespace = client.createNamespace(NAMESPACE_NAME, meta);
    assertThat(namespace).isEqualTo(NAMESPACE);
  }

  @Test
  public void testGetNamespace() throws Exception {
    final String namespaceAsJson = JsonGenerator.newJsonFor(NAMESPACE);
    when(http.get(buildUrlFor("/namespaces/%s", NAMESPACE_NAME))).thenReturn(namespaceAsJson);
    final Namespace namespace = client.getNamespace(NAMESPACE_NAME);
    assertThat(namespace).isEqualTo(NAMESPACE);
  }

  @Test
  public void testListNamespaces() throws Exception {
    when(http.get(buildUrlFor("/namespaces?limit=10&offset=0")))
        .thenReturn(Utils.toJson(new Namespaces(ImmutableList.of(NAMESPACE))));
    final List<Namespace> namespaces = client.listNamespaces(10, 0);
    assertThat(namespaces).containsExactly(NAMESPACE);
  }

  @Test
  public void testCreateSource() throws Exception {
    final URL url = buildUrlFor("/sources/%s", SOURCE_NAME);

    final SourceMeta meta =
        SourceMeta.builder()
            .type(SOURCE_TYPE)
            .connectionUrl(CONNECTION_URL)
            .description(SOURCE_DESCRIPTION)
            .build();
    final String metaAsJson = JsonGenerator.newJsonFor(meta);
    final String sourceAsJson = JsonGenerator.newJsonFor(SOURCE);
    when(http.put(url, metaAsJson)).thenReturn(sourceAsJson);

    final Source source = client.createSource(SOURCE_NAME, meta);
    assertThat(source).isEqualTo(SOURCE);
  }

  @Test
  public void testGetSource() throws Exception {
    final URL url = buildUrlFor("/sources/%s", SOURCE_NAME);

    final String sourceAsJson = JsonGenerator.newJsonFor(SOURCE);
    when(http.get(url)).thenReturn(sourceAsJson);

    final Source source = client.getSource(SOURCE_NAME);
    assertThat(source).isEqualTo(SOURCE);
  }

  @Test
  public void testListSources() throws Exception {
    when(http.get(buildUrlFor("/sources?limit=10&offset=0")))
        .thenReturn(Utils.toJson(new Sources(ImmutableList.of(SOURCE))));
    final List<Source> sources = client.listSources(10, 0);
    assertThat(sources).asList().containsExactly(SOURCE);
  }

  @Test
  public void testCreateDbTable() throws Exception {
    final DbTableMeta meta =
        DbTableMeta.builder()
            .physicalName(DB_TABLE_PHYSICAL_NAME)
            .sourceName(DB_TABLE_SOURCE_NAME)
            .fields(FIELDS)
            .tags(TAGS)
            .description(DB_TABLE_DESCRIPTION)
            .build();

    final String expectedJson = Utils.toJson(meta);
    when(http.put(marquezUrl.toDatasetUrl(NAMESPACE_NAME, DB_TABLE_NAME), expectedJson))
        .thenReturn(Utils.toJson(DB_TABLE));

    final Dataset dataset = client.createDataset(NAMESPACE_NAME, DB_TABLE_NAME, meta);
    assertThat(dataset).isEqualTo(DB_TABLE);
  }

  @Test
  public void testGetDbTable() throws Exception {
    final URL url = buildUrlFor("/namespaces/%s/datasets/%s", NAMESPACE_NAME, DB_TABLE_NAME);

    final String dbTableAsJson = Utils.getMapper().writeValueAsString(DB_TABLE);
    when(http.get(url)).thenReturn(dbTableAsJson);

    final Dataset dataset = client.getDataset(NAMESPACE_NAME, DB_TABLE_NAME);
    assertThat(dataset).isInstanceOf(DbTable.class);
    assertThat((DbTable) dataset).isEqualTo(DB_TABLE);
  }

  @Test
  public void testModifiedDbTable() throws Exception {
    final DbTableMeta meta =
        DbTableMeta.builder()
            .physicalName(DB_TABLE_PHYSICAL_NAME)
            .sourceName(DB_TABLE_SOURCE_NAME)
            .fields(FIELDS)
            .tags(TAGS)
            .description(DB_TABLE_DESCRIPTION)
            .runId(RUN_ID)
            .build();

    final String expectedJson = Utils.toJson(meta);
    when(http.put(marquezUrl.toDatasetUrl(NAMESPACE_NAME, DB_TABLE_NAME), expectedJson))
        .thenReturn(Utils.toJson(DB_TABLE_MODIFIED));

    final Dataset dataset = client.createDataset(NAMESPACE_NAME, DB_TABLE_NAME, meta);
    assertThat(dataset).isEqualTo(DB_TABLE_MODIFIED);
  }

  @Test
  public void testGetDbTableVersion() throws Exception {
    final URL url =
        buildUrlFor(
            "/namespaces/%s/datasets/%s/versions/%s", NAMESPACE_NAME, DB_TABLE_NAME, VERSION);

    final String dbTableVersionAsJson = JsonGenerator.newJsonFor(DB_TABLE_VERSION);
    when(http.get(url)).thenReturn(dbTableVersionAsJson);

    final DatasetVersion datasetVersion =
        client.getDatasetVersion(NAMESPACE_NAME, DB_TABLE_NAME, VERSION);
    assertThat(datasetVersion).isInstanceOf(DbTableVersion.class);
    assertThat((DbTableVersion) datasetVersion).isEqualTo(DB_TABLE_VERSION);
  }

  @Test
  public void testCreateStream() throws Exception {
    final URL url = buildUrlFor("/namespaces/%s/datasets/%s", NAMESPACE_NAME, STREAM_NAME);

    final StreamMeta meta =
        StreamMeta.builder()
            .physicalName(STREAM_PHYSICAL_NAME)
            .sourceName(STREAM_SOURCE_NAME)
            .fields(FIELDS)
            .tags(TAGS)
            .description(STREAM_DESCRIPTION)
            .schemaLocation(STREAM_SCHEMA_LOCATION)
            .build();
    final String metaAsJson = JsonGenerator.newJsonFor(meta);
    final String streamAsJson = Utils.getMapper().writeValueAsString(STREAM);
    when(http.put(url, metaAsJson)).thenReturn(streamAsJson);

    final Dataset dataset = client.createDataset(NAMESPACE_NAME, STREAM_NAME, meta);
    assertThat(dataset).isInstanceOf(Stream.class);
    assertThat(dataset).isEqualTo(STREAM);
  }

  @Test
  public void testGetStream() throws Exception {
    final URL url = buildUrlFor("/namespaces/%s/datasets/%s", NAMESPACE_NAME, STREAM_NAME);

    final String streamAsJson = Utils.getMapper().writeValueAsString(STREAM);
    when(http.get(url)).thenReturn(streamAsJson);

    final Dataset dataset = client.getDataset(NAMESPACE_NAME, STREAM_NAME);
    assertThat(dataset).isEqualTo(STREAM);
  }

  @Test
  public void testModifiedStream() throws Exception {
    final URL url = buildUrlFor("/namespaces/%s/datasets/%s", NAMESPACE_NAME, STREAM_NAME);

    final String streamAsJson = JsonGenerator.newJsonFor(STREAM);
    when(http.get(url)).thenReturn(streamAsJson);

    final Stream dataset = (Stream) client.getDataset(NAMESPACE_NAME, STREAM_NAME);

    final StreamMeta modifiedMeta =
        StreamMeta.builder()
            .physicalName(dataset.getPhysicalName())
            .sourceName(dataset.getSourceName())
            .fields(FIELDS)
            .tags(TAGS)
            .description(dataset.getDescription().get())
            .schemaLocation(dataset.getSchemaLocation().get())
            .runId(NEW.getId())
            .build();

    final Instant beforeModified = Instant.now();
    final String modifiedMetaAsJson = JsonGenerator.newJsonFor(modifiedMeta);
    final String modifiedStreamAsJson = Utils.getMapper().writeValueAsString(STREAM_MODIFIED);
    when(http.put(url, modifiedMetaAsJson)).thenReturn(modifiedStreamAsJson);

    final Dataset modifiedDataset = client.createDataset(NAMESPACE_NAME, STREAM_NAME, modifiedMeta);
    assertThat(modifiedDataset).isInstanceOf(Stream.class);
    assertThat((Stream) modifiedDataset).isEqualTo(STREAM_MODIFIED);
    assertThat(modifiedDataset.getLastModifiedAt().get().isAfter(beforeModified)).isFalse();
  }

  @Test
  public void testGetStreamVersion() throws Exception {
    final URL url =
        buildUrlFor(
            "/namespaces/%s/datasets/%s/versions/%s", NAMESPACE_NAME, DB_TABLE_NAME, VERSION);

    final String streamVersionAsJson = JsonGenerator.newJsonFor(STREAM_VERSION);
    when(http.get(url)).thenReturn(streamVersionAsJson);

    final DatasetVersion datasetVersion =
        client.getDatasetVersion(NAMESPACE_NAME, DB_TABLE_NAME, VERSION);
    assertThat(datasetVersion).isInstanceOf(StreamVersion.class);
    assertThat((StreamVersion) datasetVersion).isEqualTo(STREAM_VERSION);
  }

  @Test
  public void testListDatasets() throws Exception {
    Datasets datasets = new Datasets(ImmutableList.of(DB_TABLE, STREAM));
    when(http.get(buildUrlFor("/namespaces/%s/datasets?limit=10&offset=0", NAMESPACE_NAME)))
        .thenReturn(
            Utils.toJson(
                new ResultsPage<>("datasets", datasets.getValue(), datasets.getValue().size())));
    final List<Dataset> listDatasets = client.listDatasets(NAMESPACE_NAME, 10, 0);
    assertThat(listDatasets).asList().containsExactly(DB_TABLE, STREAM);
  }

  @Test
  public void testListDatasetVersions() throws Exception {
    when(http.get(
            buildUrlFor(
                "/namespaces/%s/datasets/%s/versions?limit=10&offset=0",
                NAMESPACE_NAME, DB_TABLE_NAME)))
        .thenReturn(Utils.toJson(new DatasetVersions(ImmutableList.of(DB_TABLE_VERSION))));
    final List<DatasetVersion> datasetVersions =
        client.listDatasetVersions(NAMESPACE_NAME, DB_TABLE_NAME, 10, 0);
    assertThat(datasetVersions).asList().containsExactly(DB_TABLE_VERSION);
  }

  @Test
  public void testListEvents() throws Exception {
    Events events = new Events(EVENTS);
    when(http.get(marquezUrl.toEventUrl(MarquezClient.SortDirection.DESC, 100)))
        .thenReturn(
            Utils.toJson(new ResultsPage<>("events", events.getValue(), events.getValue().size())));
    final List<LineageEvent> listEvents = client.listLineageEvents();
    assertThat(listEvents.get(0).getEventTime().toString())
        .isEqualTo(RAW_LINEAGE_EVENT.getEventTime().toString());
    assertThat(listEvents).hasSize(1);
  }

  @Test
  public void testListEventsWithSortDirection() throws Exception {
    Events events = new Events(EVENTS);
    when(http.get(marquezUrl.toEventUrl(MarquezClient.SortDirection.DESC, 5)))
        .thenReturn(
            Utils.toJson(new ResultsPage<>("events", events.getValue(), events.getValue().size())));
    final List<LineageEvent> listEvents =
        client.listLineageEvents(MarquezClient.SortDirection.DESC, 5);
    assertThat(listEvents.get(0).getEventTime().toString())
        .isEqualTo(RAW_LINEAGE_EVENT.getEventTime().toString());
    assertThat(listEvents).hasSize(1);
  }

  @Test
  public void testListEventsWithSortDirectionBeforeAfter() throws Exception {
    Events events = new Events(EVENTS);
    when(http.get(
            marquezUrl.toEventUrl(
                MarquezClient.SortDirection.DESC, BEFORE_TIMESTAMP, AFTER_TIMESTAMP, 5)))
        .thenReturn(
            Utils.toJson(new ResultsPage<>("events", events.getValue(), events.getValue().size())));
    final List<LineageEvent> listEvents =
        client.listLineageEvents(
            MarquezClient.SortDirection.DESC, BEFORE_TIMESTAMP, AFTER_TIMESTAMP, 5);
    assertThat(listEvents.get(0).getEventTime().toString())
        .isEqualTo(RAW_LINEAGE_EVENT.getEventTime().toString());
    assertThat(listEvents).hasSize(1);
  }

  @Test
  public void testCreateJob() throws Exception {
    final JobMeta meta =
        JobMeta.builder()
            .type(JOB_TYPE)
            .inputs(INPUTS)
            .outputs(OUTPUTS)
            .location(LOCATION)
            .description(JOB_DESCRIPTION)
            .build();

    final String expectedJson = Utils.toJson(meta);
    when(http.put(marquezUrl.toJobUrl(NAMESPACE_NAME, JOB_NAME), expectedJson))
        .thenReturn(Utils.toJson(JOB));

    final Job job = client.createJob(NAMESPACE_NAME, JOB_NAME, meta);
    assertThat(job).isEqualTo(JOB);
  }

  @Test
  public void testCreateJobWithRunId() throws Exception {
    final JobMeta meta =
        JobMeta.builder()
            .type(JOB_TYPE)
            .inputs(INPUTS)
            .outputs(OUTPUTS)
            .location(LOCATION)
            .description(JOB_DESCRIPTION)
            .runId(RUN_ID)
            .build();

    final String expectedJson = Utils.toJson(meta);
    when(http.put(marquezUrl.toJobUrl(NAMESPACE_NAME, JOB_NAME), expectedJson))
        .thenReturn(Utils.toJson(JOB_WITH_LATEST_RUN));

    final Job job = client.createJob(NAMESPACE_NAME, JOB_NAME, meta);
    assertThat(job).isEqualTo(JOB_WITH_LATEST_RUN);
  }

  @Test
  public void testGetJob() throws Exception {
    final URL url = buildUrlFor("/namespaces/%s/jobs/%s", NAMESPACE_NAME, JOB_NAME);

    final String jobAsJson = JsonGenerator.newJsonFor(JOB);
    when(http.get(url)).thenReturn(jobAsJson);

    final Job job = client.getJob(NAMESPACE_NAME, JOB_NAME);
    assertThat(job).isEqualTo(JOB);
  }

  @Test
  public void testListJobs() throws Exception {
    when(http.get(buildUrlFor("/namespaces/%s/jobs?limit=10&offset=0", NAMESPACE_NAME)))
        .thenReturn(Utils.toJson(new Jobs(ImmutableList.of(JOB))));
    final List<Job> jobs = client.listJobs(NAMESPACE_NAME, 10, 0);
    assertThat(jobs).asList().containsExactly(JOB);
  }

  @Test
  public void testCreateRun() throws Exception {
    final URL url = buildUrlFor("/namespaces/%s/jobs/%s/runs", NAMESPACE_NAME, JOB_NAME);

    final RunMeta meta =
        RunMeta.builder()
            .nominalStartTime(NOMINAL_START_TIME)
            .nominalEndTime(NOMINAL_END_TIME)
            .args(RUN_ARGS)
            .build();
    final String metaAsJson = JsonGenerator.newJsonFor(meta);
    final String runAsJson = JsonGenerator.newJsonFor(NEW);
    when(http.post(url, metaAsJson)).thenReturn(runAsJson);

    final Run run = client.createRun(NAMESPACE_NAME, JOB_NAME, meta);
    assertThat(run).isEqualTo(NEW);
  }

  @Test
  public void testGetRun() throws Exception {
    final URL url = buildUrlFor("/jobs/runs/%s", NEW.getId());

    final String runAsJson = JsonGenerator.newJsonFor(NEW);
    when(http.get(url)).thenReturn(runAsJson);

    final Run run = client.getRun(NEW.getId());
    assertThat(run).isEqualTo(NEW);
  }

  @Test
  public void testListRuns() throws Exception {
    when(http.get(
            buildUrlFor("/namespaces/%s/jobs/%s/runs?limit=10&offset=0", NAMESPACE_NAME, JOB_NAME)))
        .thenReturn(Utils.toJson(new Runs(ImmutableList.of(NEW))));
    final List<Run> runs = client.listRuns(NAMESPACE_NAME, JOB_NAME, 10, 0);
    assertThat(runs).asList().containsExactly(NEW);
  }

  @Test
  public void testMarkRunAsRunning() throws Exception {
    final URL url = buildUrlFor("/jobs/runs/%s/start", RUNNING.getId());

    final String runAsJson = JsonGenerator.newJsonFor(RUNNING);
    when(http.post(url)).thenReturn(runAsJson);

    final Run run = client.markRunAsRunning(RUNNING.getId());
    assertThat(run).isEqualTo(RUNNING);

    verify(http, times(1)).post(url);
  }

  @Test
  public void testMarkRunAsCompleted() throws Exception {
    final URL url = buildUrlFor("/jobs/runs/%s/complete", COMPLETED.getId());

    final String runAsJson = JsonGenerator.newJsonFor(COMPLETED);
    when(http.post(url)).thenReturn(runAsJson);

    final Run run = client.markRunAsCompleted(COMPLETED.getId());
    assertThat(run).isEqualTo(COMPLETED);

    verify(http, times(1)).post(url);
  }

  @Test
  public void testMarkRunAsAborted() throws Exception {
    final URL url = buildUrlFor("/jobs/runs/%s/abort", ABORTED.getId());

    final String runAsJson = JsonGenerator.newJsonFor(ABORTED);
    when(http.post(url)).thenReturn(runAsJson);

    final Run run = client.markRunAsAborted(ABORTED.getId());
    assertThat(run).isEqualTo(ABORTED);

    verify(http, times(1)).post(url);
  }

  @Test
  public void testMarkRunAsFailed() throws Exception {
    final URL url = buildUrlFor("/jobs/runs/%s/fail", FAILED.getId());

    final String runAsJson = JsonGenerator.newJsonFor(FAILED);
    when(http.post(url)).thenReturn(runAsJson);

    final Run run = client.markRunAsFailed(FAILED.getId());
    assertThat(run).isEqualTo(FAILED);

    verify(http, times(1)).post(url);
  }

  @Test
  public void testTagJob() throws Exception {
    final URL url =
        buildUrlFor("/namespaces/%s/jobs/%s/tags/%s", NAMESPACE_NAME, JOB_NAME, "tag_name");

    final String runAsJson = Utils.getMapper().writeValueAsString(JOB);
    when(http.post(url)).thenReturn(runAsJson);

    final Job job = client.tagJobWith(NAMESPACE_NAME, JOB_NAME, "tag_name");
    assertThat(job).isEqualTo(JOB);
  }

  @Test
  public void testDeleteJobTag() throws Exception {
    final URL url =
        buildUrlFor("/namespaces/%s/jobs/%s/tags/%s", NAMESPACE_NAME, JOB_NAME, "tag_name");

    final String runAsJson = Utils.getMapper().writeValueAsString(JOB);
    when(http.delete(url)).thenReturn(runAsJson);

    final Job job = client.deleteJobTag(NAMESPACE_NAME, JOB_NAME, "tag_name");
    assertThat(job).isEqualTo(JOB);
  }

  @Test
  public void testTagDataset() throws Exception {
    final URL url =
        buildUrlFor(
            "/namespaces/%s/datasets/%s/tags/%s", NAMESPACE_NAME, DB_TABLE_NAME, "tag_name");

    final String runAsJson = Utils.getMapper().writeValueAsString(DB_TABLE);
    when(http.post(url)).thenReturn(runAsJson);

    final Dataset dataset = client.tagDatasetWith(NAMESPACE_NAME, DB_TABLE_NAME, "tag_name");
    assertThat(dataset).isEqualTo(DB_TABLE);
  }

  @Test
  public void testDeleteDatasetTag() throws Exception {
    final URL url =
        buildUrlFor(
            "/namespaces/%s/datasets/%s/tags/%s", NAMESPACE_NAME, DB_TABLE_NAME, "tag_name");

    final String runAsJson = Utils.getMapper().writeValueAsString(DB_TABLE);
    when(http.delete(url)).thenReturn(runAsJson);

    final Dataset dataset = client.deleteDatasetTag(NAMESPACE_NAME, DB_TABLE_NAME, "tag_name");
    assertThat(dataset).isEqualTo(DB_TABLE);
  }

  @Test
  public void testTagField() throws Exception {
    final URL url =
        buildUrlFor(
            "/namespaces/%s/datasets/%s/fields/%s/tags/%s",
            NAMESPACE_NAME, DB_TABLE_NAME, "field", "tag_name");

    final String runAsJson = Utils.getMapper().writeValueAsString(DB_TABLE);
    when(http.post(url)).thenReturn(runAsJson);

    final Dataset dataset = client.tagFieldWith(NAMESPACE_NAME, DB_TABLE_NAME, "field", "tag_name");
    assertThat(dataset).isEqualTo(DB_TABLE);
  }

  @Test
  public void testDeleteTagField() throws Exception {
    final URL url =
        buildUrlFor(
            "/namespaces/%s/datasets/%s/fields/%s/tags/%s",
            NAMESPACE_NAME, DB_TABLE_NAME, "field", "tag_name");

    final String runAsJson = Utils.getMapper().writeValueAsString(DB_TABLE);
    when(http.delete(url)).thenReturn(runAsJson);

    final Dataset dataset =
        client.deleteDatasetFieldTag(NAMESPACE_NAME, DB_TABLE_NAME, "field", "tag_name");
    assertThat(dataset).isEqualTo(DB_TABLE);
  }

  @Test
  public void testListTags() throws Exception {
    ImmutableSet<Tag> expectedTags =
        ImmutableSet.of(new Tag("tag1", "a tag"), new Tag("tag2", "another tag"));
    when(http.get(buildUrlFor("/tags?limit=10&offset=0")))
        .thenReturn(Utils.toJson(new Tags(expectedTags)));
    final List<Tag> tags = new ArrayList<>(client.listTags(10, 0));
    assertThat(tags).asList().containsExactlyInAnyOrderElementsOf(expectedTags);
  }

  @Test
  public void testCreateTag() throws Exception {
    URL createTagUrl = buildUrlFor("/tags/tag2");
    MarquezClient.TagDescription tag = new MarquezClient.TagDescription("description");
    String tagDescriptionJson = tag.toJson();
    when(http.put(createTagUrl, tagDescriptionJson))
        .thenReturn(Utils.toJson(new Tag("tag2", "description")));

    Tag createdTag = client.createTag("tag2", "description");

    assertThat(createdTag.getName()).isEqualTo("tag2");
    assertThat(createdTag.getDescription()).isNotEmpty().contains("description");
  }

  @Test
  public void testGetLineage() throws Exception {
    MarquezClient.Lineage lineage = new MarquezClient.Lineage(ImmutableSet.of(LINEAGE_NODE));
    String lineageJson = lineage.toJson();
    when(http.get(buildUrlFor("/lineage?nodeId=dataset%3Anamespace%3Adataset&depth=20")))
        .thenReturn(lineageJson);

    Node retrievedNode =
        client.getLineage(NodeId.of(new DatasetId("namespace", "dataset"))).getGraph().stream()
            .findAny()
            .get();
    assertThat(retrievedNode).isEqualTo(LINEAGE_NODE);
  }

  @Test
  public void testGetColumnLineage() throws Exception {
    MarquezClient.Lineage lineage = new MarquezClient.Lineage(ImmutableSet.of(COLUMN_LINEAGE_NODE));
    String lineageJson = lineage.toJson();
    when(http.get(
            buildUrlFor(
                "/column-lineage?nodeId=dataset%3Anamespace%3Adataset&depth=20&withDownstream=false")))
        .thenReturn(lineageJson);

    Node retrievedNode =
        client
            .getColumnLineage(NodeId.of(new DatasetId("namespace", "dataset")))
            .getGraph()
            .stream()
            .findAny()
            .get();
    assertThat(retrievedNode).isEqualTo(COLUMN_LINEAGE_NODE);
  }

  private URL buildUrlFor(String pathTemplate) throws Exception {
    return new URL(DEFAULT_BASE_URL + BASE_PATH + pathTemplate);
  }

  private URL buildUrlFor(String pathTemplate, String... pathArgs) throws Exception {
    return new URL(DEFAULT_BASE_URL + BASE_PATH + String.format(pathTemplate, (Object[]) pathArgs));
  }
}

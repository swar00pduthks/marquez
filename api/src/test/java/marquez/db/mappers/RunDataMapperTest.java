package marquez.db.mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import marquez.common.models.RunState;
import marquez.db.Columns;
import marquez.service.models.RunData;
import org.jdbi.v3.core.statement.StatementContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class RunDataMapperTest {
  private ResultSet results;
  private ResultSetMetaData metaData;
  private StatementContext context;
  private RunDataMapper mapper;
  private Array childRunIdsArray;
  private Array parentRunIdsArray;
  private Array inputVersionsArray;
  private Array outputVersionsArray;

  @BeforeEach
  @Disabled
  public void setUp() throws SQLException {
    results = mock(ResultSet.class);
    metaData = mock(ResultSetMetaData.class);
    context = mock(StatementContext.class);
    childRunIdsArray = mock(Array.class);
    parentRunIdsArray = mock(Array.class);
    inputVersionsArray = mock(Array.class);
    outputVersionsArray = mock(Array.class);

    mapper = new RunDataMapper();

    // Mock metadata
    when(results.getMetaData()).thenReturn(metaData);
    when(metaData.getColumnCount()).thenReturn(20);
    when(metaData.getColumnName(1)).thenReturn(Columns.ROW_UUID);
    when(metaData.getColumnName(2)).thenReturn(Columns.CREATED_AT);
    when(metaData.getColumnName(3)).thenReturn(Columns.UPDATED_AT);
    when(metaData.getColumnName(4)).thenReturn(Columns.JOB_UUID);
    when(metaData.getColumnName(5)).thenReturn(Columns.JOB_VERSION_UUID);
    when(metaData.getColumnName(6)).thenReturn(Columns.RUN_ARGS_UUID);
    when(metaData.getColumnName(7)).thenReturn(Columns.STARTED_AT);
    when(metaData.getColumnName(8)).thenReturn(Columns.ENDED_AT);
    when(metaData.getColumnName(9)).thenReturn(Columns.CURRENT_RUN_STATE);
    when(metaData.getColumnName(10)).thenReturn(Columns.NAMESPACE_UUID);
    when(metaData.getColumnName(11)).thenReturn(Columns.NAMESPACE_NAME);
    when(metaData.getColumnName(12)).thenReturn(Columns.JOB_NAME);
    when(metaData.getColumnName(13)).thenReturn(Columns.JOB_VERSION_UUID);
    when(metaData.getColumnName(14)).thenReturn(Columns.RUN_ARGS_UUID);
    when(metaData.getColumnName(15)).thenReturn("child_run_ids");
    when(metaData.getColumnName(16)).thenReturn("parent_run_ids");
    when(metaData.getColumnName(17)).thenReturn("input_versions");
    when(metaData.getColumnName(18)).thenReturn("output_versions");

    // Mock findColumn for required columns
    when(results.findColumn(Columns.ROW_UUID)).thenReturn(1);
    when(results.findColumn(Columns.CREATED_AT)).thenReturn(2);
    when(results.findColumn(Columns.UPDATED_AT)).thenReturn(3);
    when(results.findColumn(Columns.JOB_UUID)).thenReturn(4);
    when(results.findColumn(Columns.JOB_VERSION_UUID)).thenReturn(5);
    when(results.findColumn(Columns.RUN_ARGS_UUID)).thenReturn(6);
    when(results.findColumn(Columns.STARTED_AT)).thenReturn(7);
    when(results.findColumn(Columns.ENDED_AT)).thenReturn(8);
    when(results.findColumn(Columns.CURRENT_RUN_STATE)).thenReturn(9);
    when(results.findColumn(Columns.NAMESPACE_UUID)).thenReturn(10);
    when(results.findColumn(Columns.NAMESPACE_NAME)).thenReturn(11);
    when(results.findColumn(Columns.JOB_NAME)).thenReturn(12);
    when(results.findColumn(Columns.JOB_VERSION_UUID)).thenReturn(13);
    when(results.findColumn(Columns.RUN_ARGS_UUID)).thenReturn(14);
    when(results.findColumn("child_run_ids")).thenReturn(15);
    when(results.findColumn("parent_run_ids")).thenReturn(16);
    when(results.findColumn("input_versions")).thenReturn(17);
    when(results.findColumn("output_versions")).thenReturn(18);

    // Mock column values
    UUID runUuid = UUID.randomUUID();
    UUID jobUuid = UUID.randomUUID();
    UUID jobVersionUuid = UUID.randomUUID();
    UUID runArgsUuid = UUID.randomUUID();
    UUID namespaceUuid = UUID.randomUUID();
    Instant now = Instant.now();

    when(results.getObject(Columns.ROW_UUID, UUID.class)).thenReturn(runUuid);
    when(results.getTimestamp(Columns.CREATED_AT)).thenReturn(Timestamp.from(now));
    when(results.getTimestamp(Columns.UPDATED_AT)).thenReturn(Timestamp.from(now));
    when(results.getObject(Columns.JOB_UUID, UUID.class)).thenReturn(jobUuid);
    when(results.getObject(Columns.JOB_VERSION_UUID, UUID.class)).thenReturn(jobVersionUuid);
    when(results.getObject(Columns.RUN_ARGS_UUID, UUID.class)).thenReturn(runArgsUuid);
    when(results.getTimestamp(Columns.STARTED_AT)).thenReturn(Timestamp.from(now));
    when(results.getTimestamp(Columns.ENDED_AT)).thenReturn(Timestamp.from(now));
    when(results.getString(Columns.CURRENT_RUN_STATE)).thenReturn(RunState.RUNNING.name());
    when(results.getObject(Columns.NAMESPACE_UUID, UUID.class)).thenReturn(namespaceUuid);
    when(results.getString(Columns.NAMESPACE_NAME)).thenReturn("test_namespace");
    when(results.getString(Columns.JOB_NAME)).thenReturn("test_job");

    // Mock SQL arrays
    when(results.getArray("child_run_ids")).thenReturn(childRunIdsArray);
    when(results.getArray("parent_run_ids")).thenReturn(parentRunIdsArray);
    when(results.getArray("input_versions")).thenReturn(inputVersionsArray);
    when(results.getArray("output_versions")).thenReturn(outputVersionsArray);

    // Mock array contents
    when(childRunIdsArray.getArray()).thenReturn(new UUID[] {});
    when(parentRunIdsArray.getArray()).thenReturn(new UUID[] {});
    when(inputVersionsArray.getArray())
        .thenReturn(
            new String[] {
              "{\"datasetVersion\":{\"namespace\":\"test_namespace\",\"name\":\"input_dataset\",\"version\":\"1.0\"}}"
            });
    when(outputVersionsArray.getArray())
        .thenReturn(
            new String[] {
              "{\"datasetVersion\":{\"namespace\":\"test_namespace\",\"name\":\"output_dataset\",\"version\":\"1.0\"}}"
            });
  }

  @Test
  @Disabled
  public void testMap() throws SQLException {
    RunData runData = mapper.map(results, context);

    assertThat(runData).isNotNull();
    assertThat(runData.getInputDatasetVersions())
        .hasSize(1)
        .first()
        .satisfies(
            input -> {
              assertThat(input.getDatasetVersionId().getNamespace()).isEqualTo("test_namespace");
              assertThat(input.getDatasetVersionId().getName()).isEqualTo("input_dataset");
              assertThat(input.getDatasetVersionId().getVersion()).isEqualTo("1.0");
            });

    assertThat(runData.getOutputDatasetVersions())
        .hasSize(1)
        .first()
        .satisfies(
            output -> {
              assertThat(output.getDatasetVersionId().getNamespace()).isEqualTo("test_namespace");
              assertThat(output.getDatasetVersionId().getName()).isEqualTo("output_dataset");
              assertThat(output.getDatasetVersionId().getVersion()).isEqualTo("1.0");
            });
  }
}

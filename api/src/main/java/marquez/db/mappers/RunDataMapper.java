package marquez.db.mappers;

import static marquez.db.Columns.stringOrThrow;
import static marquez.db.Columns.timestampOrNull;
import static marquez.db.Columns.timestampOrThrow;
import static marquez.db.Columns.uuidArrayOrEmpty;
import static marquez.db.Columns.uuidOrNull;
import static marquez.db.Columns.uuidOrThrow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.common.Utils;
import marquez.common.models.InputDatasetVersion;
import marquez.common.models.OutputDatasetVersion;
import marquez.common.models.RunState;
import marquez.db.Columns;
import marquez.service.models.RunData;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

@Slf4j
public class RunDataMapper implements RowMapper<RunData> {
  private static final ObjectMapper MAPPER = Utils.getMapper();

  @Override
  public RunData map(@NonNull ResultSet results, @NonNull StatementContext context)
      throws SQLException {
    return new RunData(
        uuidOrThrow(results, Columns.ROW_UUID),
        timestampOrThrow(results, Columns.CREATED_AT),
        timestampOrThrow(results, Columns.UPDATED_AT),
        timestampOrNull(results, Columns.STARTED_AT),
        timestampOrNull(results, Columns.ENDED_AT),
        RunState.valueOf(stringOrThrow(results, Columns.STATE)),
        uuidOrThrow(results, Columns.JOB_UUID),
        uuidOrNull(results, Columns.JOB_VERSION_UUID),
        stringOrThrow(results, Columns.NAMESPACE_NAME),
        stringOrThrow(results, Columns.JOB_NAME),
        ImmutableList.copyOf(uuidArrayOrEmpty(results, "input_uuids")),
        ImmutableList.copyOf(uuidArrayOrEmpty(results, "output_uuids")),
        results.getInt("depth"),
        null,
        null,
        toDatasetVersionList(results, "input_versions"),
        toOutputDatasetVersionList(results, "output_versions"),
        ImmutableList.copyOf(uuidArrayOrEmpty(results, "child_run_id")),
        ImmutableList.copyOf(uuidArrayOrEmpty(results, "parent_run_id")));
  }

  private List<InputDatasetVersion> toDatasetVersionList(ResultSet rs, String column)
      throws SQLException {
    String json = rs.getString(column);
    if (json == null) {
      return Collections.emptyList();
    }
    try {
      return MAPPER.readValue(json, new TypeReference<List<InputDatasetVersion>>() {});
    } catch (JsonProcessingException e) {
      log.error("Failed to parse JSON for column {}: {}", column, json, e);
      return Collections.emptyList();
    }
  }

  private List<OutputDatasetVersion> toOutputDatasetVersionList(ResultSet rs, String column)
      throws SQLException {
    String json = rs.getString(column);
    if (json == null) {
      return Collections.emptyList();
    }
    try {
      return MAPPER.readValue(json, new TypeReference<List<OutputDatasetVersion>>() {});
    } catch (JsonProcessingException e) {
      log.error("Failed to parse JSON for column {}: {}", column, json, e);
      return Collections.emptyList();
    }
  }
}

package marquez.db.mappers;

import static marquez.db.Columns.stringOrThrow;
import static marquez.db.Columns.timestampOrNull;
import static marquez.db.Columns.timestampOrThrow;
import static marquez.db.Columns.uuidArrayOrEmpty;
import static marquez.db.Columns.uuidOrNull;
import static marquez.db.Columns.uuidOrThrow;

import com.google.common.collect.ImmutableList;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.common.models.RunState;
import marquez.db.Columns;
import marquez.service.models.RunData;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

@Slf4j
public class RunDataMapper implements RowMapper<RunData> {
  @Override
  public RunData map(@NonNull ResultSet results, @NonNull StatementContext context)
      throws SQLException {
    UUID uuid = uuidOrThrow(results, Columns.ROW_UUID);
    return new RunData(
        uuid,
        timestampOrThrow(results, Columns.CREATED_AT),
        timestampOrThrow(results, Columns.UPDATED_AT),
        timestampOrNull(results, Columns.STARTED_AT),
        timestampOrNull(results, Columns.ENDED_AT),
        RunState.valueOf(stringOrThrow(results, Columns.STATE)),
        uuidOrThrow(results, Columns.JOB_UUID),
        uuidOrNull(results, Columns.JOB_VERSION_UUID),
        stringOrThrow(results, Columns.NAMESPACE_NAME),
        stringOrThrow(results, Columns.JOB_NAME),
        getUuidArrayOrEmpty(results, "input_uuids"),
        getUuidArrayOrEmpty(results, "output_uuids"),
        results.getInt("depth"),
        uuidOrNull(results, Columns.PARENT_RUN_UUID),
        null,
        null);
  }

  private ImmutableList<UUID> getUuidArrayOrEmpty(@NonNull ResultSet results, String column)
      throws SQLException {
    if (results.getObject(column) == null) {
      return ImmutableList.of();
    }
    try {
      return ImmutableList.copyOf(uuidArrayOrEmpty(results, column));
    } catch (Exception e) {
      log.error("Error parsing UUID array from column {}", column, e);
      return ImmutableList.of();
    }
  }
}

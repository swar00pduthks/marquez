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
import marquez.common.models.RunState;
import marquez.service.models.RunData;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

public class RunDataMapper implements RowMapper<RunData> {
  @Override
  public RunData map(@NonNull ResultSet results, @NonNull StatementContext context)
      throws SQLException {
    UUID uuid = uuidOrThrow(results, "uuid");
    return new RunData(
        uuid,
        timestampOrThrow(results, "created_at"),
        timestampOrThrow(results, "updated_at"),
        timestampOrNull(results, "started_at"),
        timestampOrNull(results, "ended_at"),
        RunState.valueOf(stringOrThrow(results, "state")),
        uuidOrThrow(results, "job_uuid"),
        uuidOrNull(results, "job_version_uuid"),
        stringOrThrow(results, "namespace_name"),
        stringOrThrow(results, "job_name"),
        ImmutableList.copyOf(uuidArrayOrEmpty(results, "input_uuids")),
        ImmutableList.copyOf(uuidArrayOrEmpty(results, "output_uuids")),
        results.getInt("depth"),
        uuidOrNull(results, "parent_run_uuid"),
        null,
        null);
  }
}

package marquez.db.mappers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import marquez.db.LineageDao.RunLineageRow;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

public class RunLineageRowMapper implements RowMapper<RunLineageRow> {

  @Override
  public RunLineageRow map(ResultSet rs, StatementContext ctx) throws SQLException {
    return new RunLineageRow(
        rs.getObject("run_uuid", UUID.class),
        rs.getObject("dataset_uuid", UUID.class),
        rs.getObject("dataset_version_uuid", UUID.class),
        rs.getString("dataset_namespace"),
        rs.getString("dataset_name"),
        rs.getObject("producer_run_uuid", UUID.class),
        rs.getString("edge_type"),
        rs.getInt("depth"),
        rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
        rs.getTimestamp("ended_at") != null ? rs.getTimestamp("ended_at").toInstant() : null,
        rs.getString("state"),
        rs.getObject("job_uuid", UUID.class),
        rs.getObject("job_version_uuid", UUID.class),
        rs.getString("job_namespace"),
        rs.getString("job_name"));
  }
}

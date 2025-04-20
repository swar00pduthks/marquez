package marquez.db.mappers;

import static java.util.stream.Collectors.toList;
import static marquez.db.Columns.stringOrThrow;
import static marquez.db.Columns.timestampOrNull;
import static marquez.db.Columns.timestampOrThrow;
import static marquez.db.Columns.uuidArrayOrEmpty;
import static marquez.db.Columns.uuidOrNull;
import static marquez.db.Columns.uuidOrThrow;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.common.Utils;
import marquez.common.models.DatasetName;
import marquez.common.models.DatasetVersionId;
import marquez.common.models.InputDatasetVersion;
import marquez.common.models.NamespaceName;
import marquez.common.models.OutputDatasetVersion;
import marquez.common.models.RunState;
import marquez.db.Columns;
import marquez.service.models.RunData;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.postgresql.util.PGobject;

@Slf4j
public class RunDataMapper implements RowMapper<RunData> {
  private static final ObjectMapper MAPPER = Utils.getMapper();

  @Override
  public RunData map(@NonNull ResultSet results, @NonNull StatementContext context)
      throws SQLException {
    Set<String> columnNames = MapperUtils.getColumnNames(results.getMetaData());
    List<QueryDatasetVersion> inputDatasetVersions =
        columnNames.contains(Columns.INPUT_VERSIONS)
            ? toQueryDatasetVersion(results, Columns.INPUT_VERSIONS)
            : ImmutableList.of();
    List<QueryDatasetVersion> outputDatasetVersions =
        columnNames.contains(Columns.OUTPUT_VERSIONS)
            ? toQueryDatasetVersion(results, Columns.OUTPUT_VERSIONS)
            : ImmutableList.of();
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
        toInputDatasetVersions(results, inputDatasetVersions, true),
        toOutputDatasetVersions(results, outputDatasetVersions, false),
        ImmutableList.copyOf(uuidArrayOrEmpty(results, "child_run_id")),
        ImmutableList.copyOf(uuidArrayOrEmpty(results, "parent_run_id")));
  }

  private List<QueryDatasetVersion> toQueryDatasetVersion(ResultSet rs, String column)
      throws SQLException {
    String dsString = rs.getString(column);
    if (dsString == null) {
      return Collections.emptyList();
    }
    return Utils.fromJson(dsString, new TypeReference<List<QueryDatasetVersion>>() {});
  }

  private List<InputDatasetVersion> toInputDatasetVersions(
      ResultSet rs, List<QueryDatasetVersion> datasetVersionIds, boolean input)
      throws SQLException {
    ImmutableList<QueryDatasetFacet> queryFacets = getQueryDatasetFacets(rs);
    try {
      return datasetVersionIds.stream()
          .map(
              version ->
                  new InputDatasetVersion(
                      version.toDatasetVersionId(), getFacetsMap(input, queryFacets, version)))
          .collect(toList());
    } catch (IllegalStateException e) {
      return Collections.emptyList();
    }
  }

  private List<OutputDatasetVersion> toOutputDatasetVersions(
      ResultSet rs, List<QueryDatasetVersion> datasetVersionIds, boolean input)
      throws SQLException {
    ImmutableList<QueryDatasetFacet> queryFacets = getQueryDatasetFacets(rs);
    try {
      return datasetVersionIds.stream()
          .map(
              version ->
                  new OutputDatasetVersion(
                      version.toDatasetVersionId(), getFacetsMap(input, queryFacets, version)))
          .collect(toList());
    } catch (IllegalStateException e) {
      return Collections.emptyList();
    }
  }

  private ImmutableMap<String, Object> getFacetsMap(
      boolean input,
      ImmutableList<QueryDatasetFacet> queryDatasetFacets,
      QueryDatasetVersion queryDatasetVersion) {
    return ImmutableMap.copyOf(
        queryDatasetFacets.stream()
            .filter(rf -> rf.type.equalsIgnoreCase(input ? "input" : "output"))
            .filter(rf -> rf.datasetVersionUUID.equals(queryDatasetVersion.datasetVersionUUID))
            .collect(
                Collectors.toMap(
                    QueryDatasetFacet::name,
                    facet ->
                        Utils.getMapper()
                            .convertValue(
                                Utils.getMapper().valueToTree(facet.facet).get(facet.name),
                                Object.class),
                    (a1, a2) -> a2 // in case of duplicates, choose more recent
                    )));
  }

  private ImmutableList<QueryDatasetFacet> getQueryDatasetFacets(ResultSet resultSet)
      throws SQLException {
    String column = Columns.DATASET_FACETS;
    ImmutableList<QueryDatasetFacet> queryDatasetFacets = ImmutableList.of();
    if (Columns.exists(resultSet, column) && resultSet.getObject(column) != null) {
      try {
        queryDatasetFacets =
            MAPPER.readValue(
                ((PGobject) resultSet.getObject(column)).getValue(),
                new TypeReference<ImmutableList<QueryDatasetFacet>>() {});
      } catch (JsonProcessingException e) {
        log.error(String.format("Could not read dataset from job row %s", column), e);
      }
    }
    return queryDatasetFacets;
  }

  record QueryDatasetFacet(
      @JsonProperty("dataset_version_uuid") String datasetVersionUUID,
      String name,
      String type,
      Object facet) {}

  record QueryDatasetVersion(
      String namespace,
      String name,
      UUID version,
      // field required to merge input versions with input dataset facets
      @JsonProperty("dataset_version_uuid") String datasetVersionUUID) {
    public DatasetVersionId toDatasetVersionId() {
      return DatasetVersionId.builder()
          .name(DatasetName.of(name))
          .namespace(NamespaceName.of(namespace))
          .version(version)
          .build();
    }
  }
}

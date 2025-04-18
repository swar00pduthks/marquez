/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Arrays;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.stream.StreamSupport;
import lombok.NonNull;
import marquez.common.Utils;
import marquez.service.models.LineageEvent;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.postgresql.util.PGobject;

/** The DAO for {@code dataset} facets. */
public interface DatasetFacetsDao {
  /* An {@code enum} used ... */
  enum Type {
    DATASET,
    INPUT,
    OUTPUT,
    UNKNOWN;
  }

  /* An {@code enum} used to determine the dataset facet. */
  enum DatasetFacet {
    DOCUMENTATION(Type.DATASET, "documentation"),
    DESCRIPTION(Type.DATASET, "description"),
    SCHEMA(Type.DATASET, "schema"),
    DATASOURCE(Type.DATASET, "dataSource"),
    LIFECYCLE_STATE_CHANGE(Type.DATASET, "lifecycleStateChange"),
    VERSION(Type.DATASET, "version"),
    COLUMN_LINEAGE(Type.DATASET, "columnLineage"),
    OWNERSHIP(Type.DATASET, "ownership"),
    DATA_QUALITY_METRICS(Type.INPUT, "dataQualityMetrics"),
    DATA_QUALITY_ASSERTIONS(Type.INPUT, "dataQualityAssertions"),
    OUTPUT_STATISTICS(Type.OUTPUT, "outputStatistics");

    final Type type;
    final String name;

    DatasetFacet(@NonNull final Type type, @NonNull final String name) {
      this.type = type;
      this.name = name;
    }

    Type getType() {
      return type;
    }

    String getName() {
      return name;
    }

    /** ... */
    public static Type typeFromName(@NonNull final String name) {
      return Arrays.stream(DatasetFacet.values())
          .filter(facet -> facet.getName().equalsIgnoreCase(name))
          .map(facet -> facet.getType())
          .findFirst()
          .orElse(Type.UNKNOWN);
    }
  }

  /**
   * @param createdAt
   * @param datasetUuid
   * @param datasetVersionUuid
   * @param runUuid
   * @param lineageEventTime
   * @param lineageEventType
   * @param type
   * @param name
   * @param facet
   */
  @SqlUpdate(
      """
          INSERT INTO dataset_facets (
             created_at,
             dataset_uuid,
             dataset_version_uuid,
             run_uuid,
             lineage_event_time,
             lineage_event_type,
             type,
             name,
             facet
          ) VALUES (
             :createdAt,
             :datasetUuid,
             :datasetVersionUuid,
             :runUuid,
             :lineageEventTime,
             :lineageEventType,
             :type,
             :name,
             :facet
          )
      """)
  void insertDatasetFacet(
      Instant createdAt,
      UUID datasetUuid,
      UUID datasetVersionUuid,
      UUID runUuid,
      Instant lineageEventTime,
      String lineageEventType,
      Type type,
      String name,
      PGobject facet);

  /**
   * @param datasetUuid
   * @param runUuid
   * @param lineageEventTime
   * @param lineageEventType
   * @param datasetFacets
   */
  @Transaction
  default void insertDatasetFacetsFor(
      @NonNull UUID datasetUuid,
      @NonNull UUID datasetVersionUuid,
      @Nullable UUID runUuid,
      @NonNull Instant lineageEventTime,
      @Nullable String lineageEventType,
      @NonNull LineageEvent.DatasetFacets datasetFacets) {
    final Instant now = Instant.now();

    JsonNode jsonNode = Utils.getMapper().valueToTree(datasetFacets);
    StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(jsonNode.fieldNames(), Spliterator.DISTINCT), false)
        .forEach(
            fieldName ->
                insertDatasetFacet(
                    now,
                    datasetUuid,
                    datasetVersionUuid,
                    runUuid,
                    lineageEventTime,
                    lineageEventType,
                    DatasetFacet.typeFromName(fieldName),
                    fieldName,
                    FacetUtils.toPgObject(fieldName, jsonNode.get(fieldName))));
  }

  default void insertInputDatasetFacetsFor(
      @NonNull UUID datasetUuid,
      @NonNull UUID datasetVersionUuid,
      @Nullable UUID runUuid,
      @NonNull Instant lineageEventTime,
      @Nullable String lineageEventType,
      @NonNull LineageEvent.InputDatasetFacets inputFacets) {
    final Instant now = Instant.now();

    JsonNode jsonNode = Utils.getMapper().valueToTree(inputFacets);
    StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(jsonNode.fieldNames(), Spliterator.DISTINCT), false)
        .forEach(
            fieldName ->
                insertDatasetFacet(
                    now,
                    datasetUuid,
                    datasetVersionUuid,
                    runUuid,
                    lineageEventTime,
                    lineageEventType,
                    Type.INPUT,
                    fieldName,
                    FacetUtils.toPgObject(fieldName, jsonNode.get(fieldName))));
  }

  default void insertOutputDatasetFacetsFor(
      @NonNull UUID datasetUuid,
      @NonNull UUID datasetVersionUuid,
      @Nullable UUID runUuid,
      @NonNull Instant lineageEventTime,
      @Nullable String lineageEventType,
      @NonNull LineageEvent.OutputDatasetFacets outputFacets) {
    final Instant now = Instant.now();

    JsonNode jsonNode = Utils.getMapper().valueToTree(outputFacets);
    StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(jsonNode.fieldNames(), Spliterator.DISTINCT), false)
        .forEach(
            fieldName ->
                insertDatasetFacet(
                    now,
                    datasetUuid,
                    datasetVersionUuid,
                    runUuid,
                    lineageEventTime,
                    lineageEventType,
                    Type.OUTPUT,
                    fieldName,
                    FacetUtils.toPgObject(fieldName, jsonNode.get(fieldName))));
  }

  record DatasetFacetRow(
      Instant createdAt,
      UUID datasetUuid,
      UUID datasetVersionUuid,
      UUID runUuid,
      Instant lineageEventTime,
      String lineageEventType,
      DatasetFacetsDao.Type type,
      String name,
      PGobject facet) {}
}

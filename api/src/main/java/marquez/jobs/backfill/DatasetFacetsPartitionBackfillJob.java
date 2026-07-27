/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.jobs.backfill;

import java.util.List;
import lombok.NonNull;
import marquez.jobs.BackfillConfig;
import org.jdbi.v3.core.Jdbi;

/**
 * Online cutover that partitions {@code dataset_facets} on a large existing database (V111). See
 * {@link AbstractPartitionCutoverBackfillJob} for the copy + verified-swap mechanics.
 */
public class DatasetFacetsPartitionBackfillJob extends AbstractPartitionCutoverBackfillJob {

  public static final String VERSION = "DATASET_FACETS_PARTITION_V1";

  public DatasetFacetsPartitionBackfillJob(@NonNull Jdbi jdbi, @NonNull BackfillConfig config) {
    super(jdbi, config);
  }

  @Override
  public String version() {
    return VERSION;
  }

  @Override
  protected String table() {
    return "dataset_facets";
  }

  @Override
  protected String shadow() {
    return "dataset_facets_p";
  }

  @Override
  protected String stateTable() {
    return "dataset_facets_partition_state";
  }

  @Override
  protected String triggerName() {
    return "dataset_facets_mirror_trg";
  }

  @Override
  protected String boundaryColumn() {
    return "created_at";
  }

  @Override
  protected String insertColumns() {
    return "created_at, dataset_uuid, dataset_version_uuid, run_uuid, lineage_event_time,"
        + " lineage_event_type, type, name, facet, namespace";
  }

  @Override
  protected String selectExpr() {
    return "o.created_at, o.dataset_uuid, o.dataset_version_uuid, o.run_uuid, o.lineage_event_time,"
        + " o.lineage_event_type, o.type, o.name, o.facet,"
        + " COALESCE(o.namespace, (SELECT namespace_name FROM runs WHERE uuid = o.run_uuid))";
  }

  @Override
  protected List<String> preSwapDdl() {
    return List.of("DROP VIEW IF EXISTS dataset_facets_view");
  }

  @Override
  protected List<String> postSwapDdl() {
    return List.of(
        "ALTER TABLE dataset_facets ADD CONSTRAINT dataset_facets_dataset_uuid_fkey"
            + " FOREIGN KEY (dataset_uuid) REFERENCES datasets (uuid) ON DELETE CASCADE",
        "ALTER TABLE dataset_facets ADD CONSTRAINT dataset_facets_dataset_version_uuid_fkey"
            + " FOREIGN KEY (dataset_version_uuid) REFERENCES dataset_versions (uuid) ON DELETE"
            + " CASCADE",
        "ALTER TABLE dataset_facets ADD CONSTRAINT dataset_facets_run_uuid_fkey"
            + " FOREIGN KEY (run_uuid) REFERENCES runs (uuid) ON DELETE CASCADE",
        "CREATE VIEW dataset_facets_view AS SELECT created_at, dataset_uuid, dataset_version_uuid,"
            + " run_uuid, lineage_event_time, lineage_event_type, type, name, facet FROM"
            + " dataset_facets");
  }
}

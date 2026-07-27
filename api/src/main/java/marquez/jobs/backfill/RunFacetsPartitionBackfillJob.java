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
 * Online cutover that partitions {@code run_facets} on a large existing database (V108). See {@link
 * AbstractPartitionCutoverBackfillJob} for the copy + verified-swap mechanics.
 */
public class RunFacetsPartitionBackfillJob extends AbstractPartitionCutoverBackfillJob {

  public static final String VERSION = "RUN_FACETS_PARTITION_V1";

  public RunFacetsPartitionBackfillJob(@NonNull Jdbi jdbi, @NonNull BackfillConfig config) {
    super(jdbi, config);
  }

  @Override
  public String version() {
    return VERSION;
  }

  @Override
  protected String table() {
    return "run_facets";
  }

  @Override
  protected String shadow() {
    return "run_facets_p";
  }

  @Override
  protected String stateTable() {
    return "run_facets_partition_state";
  }

  @Override
  protected String triggerName() {
    return "run_facets_mirror_trg";
  }

  @Override
  protected String boundaryColumn() {
    return "created_at";
  }

  @Override
  protected String insertColumns() {
    return "created_at, run_uuid, lineage_event_time, lineage_event_type, name, facet, namespace";
  }

  @Override
  protected String selectExpr() {
    return "o.created_at, o.run_uuid, o.lineage_event_time, o.lineage_event_type, o.name, o.facet,"
        + " COALESCE(o.namespace, (SELECT namespace_name FROM runs WHERE uuid = o.run_uuid))";
  }

  @Override
  protected List<String> preSwapDdl() {
    return List.of("DROP VIEW IF EXISTS run_facets_view");
  }

  @Override
  protected List<String> postSwapDdl() {
    return List.of(
        "ALTER TABLE run_facets ADD CONSTRAINT run_facets_run_uuid_fkey"
            + " FOREIGN KEY (run_uuid) REFERENCES runs (uuid) ON DELETE CASCADE",
        "CREATE VIEW run_facets_view AS SELECT created_at, run_uuid, lineage_event_time,"
            + " lineage_event_type, name, facet FROM run_facets");
  }
}

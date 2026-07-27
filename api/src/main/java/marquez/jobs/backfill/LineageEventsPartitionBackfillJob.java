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
 * Online cutover that partitions {@code lineage_events} on a large existing database (V112). See
 * {@link AbstractPartitionCutoverBackfillJob} for the copy + verified-swap mechanics.
 *
 * <p>Two differences from the facet tables: it HASH-partitions on the existing {@code
 * job_namespace} (no namespace resolution), and its dependent is the actively refreshed matview
 * {@code lineage_events_by_type_hourly_view} — dropped before the rename, recreated {@code WITH NO
 * DATA} after (instant, keeping the swap lock short), then repopulated by {@link
 * #afterSwapCommitted()}. Historical rows may predate the {@code created_at} column, so NULLs are
 * treated as historical.
 */
public class LineageEventsPartitionBackfillJob extends AbstractPartitionCutoverBackfillJob {

  public static final String VERSION = "LINEAGE_EVENTS_PARTITION_V1";

  private static final String MATVIEW_DEF =
      "CREATE MATERIALIZED VIEW lineage_events_by_type_hourly_view AS SELECT date_trunc('hour',"
          + " event_time) AS start_interval, count(*) FILTER (WHERE event_type = 'FAIL') AS fail,"
          + " count(*) FILTER (WHERE event_type = 'START') AS start, count(*) FILTER (WHERE"
          + " event_type = 'COMPLETE') AS complete, count(*) FILTER (WHERE event_type = 'ABORT') AS"
          + " abort FROM lineage_events GROUP BY date_trunc('hour', event_time)";

  public LineageEventsPartitionBackfillJob(@NonNull Jdbi jdbi, @NonNull BackfillConfig config) {
    super(jdbi, config);
  }

  @Override
  public String version() {
    return VERSION;
  }

  @Override
  protected String table() {
    return "lineage_events";
  }

  @Override
  protected String shadow() {
    return "lineage_events_p";
  }

  @Override
  protected String stateTable() {
    return "lineage_events_partition_state";
  }

  @Override
  protected String triggerName() {
    return "lineage_events_mirror_trg";
  }

  @Override
  protected String boundaryColumn() {
    return "created_at";
  }

  @Override
  protected String copyBoundaryPredicate() {
    // created_at was added later; pre-existing rows may be NULL. New rows always get its
    // DEFAULT now(), so NULLs only exist in history — treat them as the backfill's to own.
    return "created_at < :cutover OR created_at IS NULL";
  }

  @Override
  protected String insertColumns() {
    return "event_time, event, event_type, job_name, job_namespace, producer, run_uuid,"
        + " created_at, _event_type, run_date";
  }

  @Override
  protected String selectExpr() {
    return "o.event_time, o.event, o.event_type, o.job_name, o.job_namespace, o.producer,"
        + " o.run_uuid, o.created_at, o._event_type, o.run_date";
  }

  @Override
  protected List<String> preSwapDdl() {
    return List.of("DROP MATERIALIZED VIEW IF EXISTS lineage_events_by_type_hourly_view");
  }

  @Override
  protected List<String> postSwapDdl() {
    // WITH NO DATA keeps the swap lock instant; afterSwapCommitted() populates it.
    return List.of(MATVIEW_DEF + " WITH NO DATA");
  }

  @Override
  protected void afterSwapCommitted() {
    jdbi.useHandle(
        handle -> handle.execute("REFRESH MATERIALIZED VIEW lineage_events_by_type_hourly_view"));
  }
}

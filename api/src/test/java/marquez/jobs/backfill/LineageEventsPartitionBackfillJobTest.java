/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.jobs.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import marquez.api.JdbiUtils;
import marquez.jdbi.MarquezJdbiExternalPostgresExtension;
import marquez.jobs.BackfillConfig;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Exercises the online (large-table) cutover path of {@link LineageEventsPartitionBackfillJob},
 * covering the two lineage_events-specific concerns: pre-existing rows with NULL {@code created_at}
 * (treated as historical), and the actively-refreshed matview {@code
 * lineage_events_by_type_hourly_view} being dropped, recreated, and repopulated across the swap.
 */
@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
public class LineageEventsPartitionBackfillJobTest {

  private static Jdbi jdbi;
  private static final String CUTOVER = "2026-06-01 00:00:00+00";

  @BeforeAll
  public static void setUpOnce(Jdbi jdbi) {
    LineageEventsPartitionBackfillJobTest.jdbi = jdbi;
  }

  @AfterEach
  public void tearDown(Jdbi jdbi) {
    JdbiUtils.cleanDatabase(jdbi);
  }

  @Test
  public void testOnlineCutoverCopiesExactlyOnceWithNullBoundaryAndRefreshesMatview() {
    jdbi.useHandle(
        h -> {
          // Shared test DB: reset this version's checkpoint so the job runs fresh.
          h.execute(
              "DELETE FROM backfill_checkpoints WHERE version = '"
                  + LineageEventsPartitionBackfillJob.VERSION
                  + "'");
          h.execute("DROP MATERIALIZED VIEW IF EXISTS lineage_events_by_type_hourly_view");
          h.execute("ALTER TABLE lineage_events RENAME TO lineage_events_p");
          h.execute(
              "CREATE TABLE lineage_events (LIKE lineage_events_p INCLUDING DEFAULTS INCLUDING"
                  + " INDEXES)");

          // 600 historical with created_at < cutover.
          h.execute(
              "INSERT INTO lineage_events (event_time, event, event_type, job_name, job_namespace,"
                  + " producer, run_uuid, created_at, _event_type, run_date) SELECT '2026-05-01"
                  + " 00:00:00+00'::timestamptz + (g||' seconds')::interval, '{}'::jsonb, 'COMPLETE',"
                  + " 'j'||(g%3), 'ns'||(g%3), 'p', gen_random_uuid(), '2026-05-01"
                  + " 00:00:00+00'::timestamptz + (g||' seconds')::interval, 'RUN_EVENT',"
                  + " '2026-05-01' FROM generate_series(1,600) g");
          // 200 historical with NULL created_at (predate the column).
          h.execute(
              "INSERT INTO lineage_events (event_time, event, event_type, job_name, job_namespace,"
                  + " producer, run_uuid, created_at, _event_type, run_date) SELECT '2026-04-01"
                  + " 00:00:00+00'::timestamptz + (g||' seconds')::interval, '{}'::jsonb, 'START',"
                  + " 'j'||(g%3), 'ns'||(g%3), 'p', gen_random_uuid(), NULL, 'RUN_EVENT',"
                  + " '2026-04-01' FROM generate_series(1,200) g");

          h.execute(
              "CREATE TRIGGER lineage_events_mirror_trg AFTER INSERT ON lineage_events FOR EACH ROW"
                  + " WHEN (NEW.created_at >= '"
                  + CUTOVER
                  + "'::timestamptz) EXECUTE FUNCTION lineage_events_mirror_to_partition()");
          h.execute(
              "INSERT INTO lineage_events_partition_state (singleton, state, cutover_at) VALUES"
                  + " (true, 'DUAL_WRITE', '"
                  + CUTOVER
                  + "') ON CONFLICT (singleton) DO UPDATE SET state='DUAL_WRITE',"
                  + " cutover_at=EXCLUDED.cutover_at");
          // 50 live rows (created_at >= cutover) mirrored by the trigger.
          h.execute(
              "INSERT INTO lineage_events (event_time, event, event_type, job_name, job_namespace,"
                  + " producer, run_uuid, created_at, _event_type, run_date) SELECT '2026-06-02"
                  + " 00:00:00+00'::timestamptz + (g||' seconds')::interval, '{}'::jsonb, 'COMPLETE',"
                  + " 'j'||(g%3), 'ns'||(g%3), 'p', gen_random_uuid(), '2026-06-02"
                  + " 00:00:00+00'::timestamptz + (g||' seconds')::interval, 'RUN_EVENT',"
                  + " '2026-06-02' FROM generate_series(1,50) g");
        });

    BackfillConfig cfg = BackfillConfig.builder().batchSize(13).delayBetweenBatchesMs(0).build();
    try {
      new LineageEventsPartitionBackfillJob(jdbi, cfg).run();
    } catch (Exception e) {
      throw new AssertionError("cutover job threw: " + e.getMessage(), e);
    }

    jdbi.useHandle(
        h -> {
          assertThat(
                  h.createQuery(
                          "SELECT EXISTS(SELECT 1 FROM pg_partitioned_table WHERE partrelid ="
                              + " 'lineage_events'::regclass)")
                      .mapTo(Boolean.class)
                      .one())
              .withFailMessage("lineage_events should be partitioned")
              .isTrue();
          assertThat(h.createQuery("SELECT count(*) FROM lineage_events").mapTo(Long.class).one())
              .withFailMessage(
                  "all 800 historical (incl 200 NULL created_at) + 50 live must survive")
              .isEqualTo(850L);
          assertThat(
                  h.createQuery("SELECT count(*) FROM lineage_events WHERE created_at IS NULL")
                      .mapTo(Long.class)
                      .one())
              .withFailMessage("NULL created_at rows must be copied, not dropped")
              .isEqualTo(200L);
          assertThat(
                  h.createQuery("SELECT state FROM lineage_events_partition_state")
                      .mapTo(String.class)
                      .one())
              .isEqualTo("SWAPPED");
          assertThat(
                  h.createQuery("SELECT to_regclass('lineage_events_p') IS NULL")
                      .mapTo(Boolean.class)
                      .one())
              .withFailMessage("shadow lineage_events_p should be gone")
              .isTrue();
          assertThat(
                  h.createQuery(
                          "SELECT count(*)=0 FROM pg_trigger WHERE"
                              + " tgname='lineage_events_mirror_trg'")
                      .mapTo(Boolean.class)
                      .one())
              .withFailMessage("mirror trigger should be dropped")
              .isTrue();
          // Matview recreated AND repopulated by afterSwapCommitted() — scannable + non-empty.
          assertThat(
                  h.createQuery("SELECT count(*) FROM lineage_events_by_type_hourly_view")
                      .mapTo(Long.class)
                      .one())
              .withFailMessage("matview should be recreated and refreshed (populated)")
              .isGreaterThan(0L);
        });
  }
}

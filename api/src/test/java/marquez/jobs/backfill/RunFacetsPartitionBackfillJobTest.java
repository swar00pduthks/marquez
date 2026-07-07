/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.jobs.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import marquez.api.JdbiUtils;
import marquez.jdbi.MarquezJdbiExternalPostgresExtension;
import marquez.jobs.BackfillConfig;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Exercises the online (large-table) cutover path of {@link RunFacetsPartitionBackfillJob}: reshape
 * run_facets into the DUAL_WRITE state V108 leaves on a large database, seed history + live
 * (trigger-mirrored) rows, run the job, and assert it copies exactly-once and swaps.
 */
@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
public class RunFacetsPartitionBackfillJobTest {

  private static Jdbi jdbi;
  private static final UUID RUN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  private static final String CUTOVER = "2026-06-01 00:00:00+00";

  @BeforeAll
  public static void setUpOnce(Jdbi jdbi) {
    RunFacetsPartitionBackfillJobTest.jdbi = jdbi;
  }

  @AfterEach
  public void tearDown(Jdbi jdbi) {
    JdbiUtils.cleanDatabase(jdbi);
  }

  @Test
  public void testOnlineCutoverCopiesExactlyOnceAndSwaps() {
    // Reshape into the large-path armed state: the partitioned table becomes the empty shadow
    // run_facets_p (no FK, as LIKE leaves it in V108), and a fresh plain run_facets holds history.
    jdbi.useHandle(
        h -> {
          h.execute("DROP VIEW IF EXISTS run_facets_view");
          h.execute("ALTER TABLE run_facets RENAME TO run_facets_p");
          h.execute("CREATE TABLE run_facets (LIKE run_facets_p INCLUDING DEFAULTS)");
          h.execute("ALTER TABLE run_facets_p DROP CONSTRAINT IF EXISTS run_facets_run_uuid_fkey");

          // A namespace + run so the copy's COALESCE(namespace, subselect on runs) resolves NULLs.
          h.execute(
              "INSERT INTO namespaces (uuid, created_at, updated_at, name, current_owner_name)"
                  + " VALUES (gen_random_uuid(), now(), now(), 'nsX', 'owner') ON CONFLICT DO NOTHING");
          h.execute(
              "INSERT INTO runs (uuid, created_at, updated_at, current_run_state, namespace_name,"
                  + " job_name, job_uuid) VALUES (?, now(), now(), 'COMPLETED', 'nsX', 'j',"
                  + " gen_random_uuid())",
              RUN);

          // 900 historical rows (created_at < cutover); every other one has NULL namespace to force
          // the subselect resolution during the copy.
          h.execute(
              "INSERT INTO run_facets (created_at, run_uuid, lineage_event_time, lineage_event_type,"
                  + " name, facet, namespace) SELECT '2026-05-01 00:00:00+00'::timestamptz +"
                  + " (g||' seconds')::interval, ?, '2026-05-01'::timestamptz, 'COMPLETE', 'f'||g,"
                  + " '{}'::jsonb, CASE WHEN g % 2 = 0 THEN NULL ELSE 'nsX' END"
                  + " FROM generate_series(1,900) g",
              RUN);

          // Arm dual-write (function created by V108) + marker, then 40 live rows the trigger
          // mirrors.
          h.execute(
              "CREATE TRIGGER run_facets_mirror_trg AFTER INSERT ON run_facets FOR EACH ROW"
                  + " WHEN (NEW.created_at >= '"
                  + CUTOVER
                  + "'::timestamptz) EXECUTE FUNCTION run_facets_mirror_to_partition()");
          h.execute(
              "INSERT INTO run_facets_partition_state (singleton, state, cutover_at) VALUES (true,"
                  + " 'DUAL_WRITE', '"
                  + CUTOVER
                  + "') ON CONFLICT (singleton) DO UPDATE SET state='DUAL_WRITE',"
                  + " cutover_at=EXCLUDED.cutover_at");
          h.execute(
              "INSERT INTO run_facets (created_at, run_uuid, lineage_event_time, lineage_event_type,"
                  + " name, facet, namespace) SELECT '2026-06-02 00:00:00+00'::timestamptz +"
                  + " (g||' seconds')::interval, ?, '2026-06-02'::timestamptz, 'COMPLETE',"
                  + " 'live'||g, '{}'::jsonb, 'nsX' FROM generate_series(1,40) g",
              RUN);
        });

    // Small batch size to exercise many keyset batches (the tid-vs-text ordering regression).
    BackfillConfig cfg = BackfillConfig.builder().batchSize(7).delayBetweenBatchesMs(0).build();
    try {
      new RunFacetsPartitionBackfillJob(jdbi, cfg).run();
    } catch (Exception e) {
      throw new AssertionError("cutover job threw: " + e.getMessage(), e);
    }

    jdbi.useHandle(
        h -> {
          boolean partitioned =
              h.createQuery(
                      "SELECT EXISTS(SELECT 1 FROM pg_partitioned_table WHERE partrelid ="
                          + " 'run_facets'::regclass)")
                  .mapTo(Boolean.class)
                  .one();
          long count = h.createQuery("SELECT count(*) FROM run_facets").mapTo(Long.class).one();
          long nullNs =
              h.createQuery("SELECT count(*) FROM run_facets WHERE namespace IS NULL")
                  .mapTo(Long.class)
                  .one();
          String state =
              h.createQuery("SELECT state FROM run_facets_partition_state")
                  .mapTo(String.class)
                  .one();
          boolean shadowGone =
              h.createQuery("SELECT to_regclass('run_facets_p') IS NULL")
                  .mapTo(Boolean.class)
                  .one();
          boolean triggerGone =
              h.createQuery(
                      "SELECT count(*)=0 FROM pg_trigger WHERE tgname='run_facets_mirror_trg'")
                  .mapTo(Boolean.class)
                  .one();
          boolean viewOk =
              h.createQuery("SELECT to_regclass('run_facets_view') IS NOT NULL")
                  .mapTo(Boolean.class)
                  .one();
          boolean fkOnParent =
              h.createQuery(
                      "SELECT count(*)=1 FROM pg_constraint WHERE conname='run_facets_run_uuid_fkey'"
                          + " AND conrelid='run_facets'::regclass")
                  .mapTo(Boolean.class)
                  .one();

          assertThat(partitioned).withFailMessage("run_facets should be partitioned").isTrue();
          assertThat(count)
              .withFailMessage("all 900 historical + 40 live rows must survive exactly-once")
              .isEqualTo(940L);
          assertThat(nullNs)
              .withFailMessage("NULL namespaces must be resolved from runs during the copy")
              .isEqualTo(0L);
          assertThat(state).isEqualTo("SWAPPED");
          assertThat(shadowGone).withFailMessage("shadow run_facets_p should be gone").isTrue();
          assertThat(triggerGone).withFailMessage("mirror trigger should be dropped").isTrue();
          assertThat(viewOk).withFailMessage("run_facets_view should be recreated").isTrue();
          assertThat(fkOnParent).withFailMessage("FK should be restored on run_facets").isTrue();
        });
  }
}

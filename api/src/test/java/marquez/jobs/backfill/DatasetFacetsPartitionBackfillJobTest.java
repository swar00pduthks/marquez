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
 * Exercises the online (large-table) cutover path of {@link DatasetFacetsPartitionBackfillJob}:
 * reshape dataset_facets into the DUAL_WRITE state V111 leaves on a large database, seed history +
 * live (trigger-mirrored) rows, run the job, and assert it copies exactly-once and swaps (restoring
 * all three FKs + the view).
 */
@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
public class DatasetFacetsPartitionBackfillJobTest {

  private static Jdbi jdbi;
  private static final UUID RUN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  private static final String CUTOVER = "2026-06-01 00:00:00+00";

  @BeforeAll
  public static void setUpOnce(Jdbi jdbi) {
    DatasetFacetsPartitionBackfillJobTest.jdbi = jdbi;
  }

  @AfterEach
  public void tearDown(Jdbi jdbi) {
    JdbiUtils.cleanDatabase(jdbi);
  }

  @Test
  public void testOnlineCutoverCopiesExactlyOnceAndSwaps() {
    jdbi.useHandle(
        h -> {
          h.execute("DROP VIEW IF EXISTS dataset_facets_view");
          h.execute("ALTER TABLE dataset_facets RENAME TO dataset_facets_p");
          h.execute("CREATE TABLE dataset_facets (LIKE dataset_facets_p INCLUDING DEFAULTS)");
          h.execute(
              "ALTER TABLE dataset_facets_p DROP CONSTRAINT IF EXISTS"
                  + " dataset_facets_dataset_uuid_fkey");
          h.execute(
              "ALTER TABLE dataset_facets_p DROP CONSTRAINT IF EXISTS"
                  + " dataset_facets_dataset_version_uuid_fkey");
          h.execute(
              "ALTER TABLE dataset_facets_p DROP CONSTRAINT IF EXISTS dataset_facets_run_uuid_fkey");

          h.execute(
              "INSERT INTO namespaces (uuid, created_at, updated_at, name, current_owner_name)"
                  + " VALUES (gen_random_uuid(), now(), now(), 'nsX', 'owner') ON CONFLICT DO NOTHING");
          h.execute(
              "INSERT INTO runs (uuid, created_at, updated_at, current_run_state, namespace_name,"
                  + " job_name, job_uuid) VALUES (?, now(), now(), 'COMPLETED', 'nsX', 'j',"
                  + " gen_random_uuid())",
              RUN);

          // 700 historical rows (created_at < cutover); every other one NULL namespace.
          h.execute(
              "INSERT INTO dataset_facets (created_at, dataset_uuid, dataset_version_uuid, run_uuid,"
                  + " lineage_event_time, lineage_event_type, type, name, facet, namespace)"
                  + " SELECT '2026-05-01 00:00:00+00'::timestamptz + (g||' seconds')::interval, NULL,"
                  + " NULL, ?, '2026-05-01'::timestamptz, 'COMPLETE', 'DATASET', 'df'||g, '{}'::jsonb,"
                  + " CASE WHEN g % 2 = 0 THEN NULL ELSE 'nsX' END FROM generate_series(1,700) g",
              RUN);

          h.execute(
              "CREATE TRIGGER dataset_facets_mirror_trg AFTER INSERT ON dataset_facets FOR EACH ROW"
                  + " WHEN (NEW.created_at >= '"
                  + CUTOVER
                  + "'::timestamptz) EXECUTE FUNCTION dataset_facets_mirror_to_partition()");
          h.execute(
              "INSERT INTO dataset_facets_partition_state (singleton, state, cutover_at) VALUES"
                  + " (true, 'DUAL_WRITE', '"
                  + CUTOVER
                  + "') ON CONFLICT (singleton) DO UPDATE SET state='DUAL_WRITE',"
                  + " cutover_at=EXCLUDED.cutover_at");
          h.execute(
              "INSERT INTO dataset_facets (created_at, dataset_uuid, dataset_version_uuid, run_uuid,"
                  + " lineage_event_time, lineage_event_type, type, name, facet, namespace)"
                  + " SELECT '2026-06-02 00:00:00+00'::timestamptz + (g||' seconds')::interval, NULL,"
                  + " NULL, ?, '2026-06-02'::timestamptz, 'COMPLETE', 'DATASET', 'dlive'||g,"
                  + " '{}'::jsonb, 'nsX' FROM generate_series(1,30) g",
              RUN);
        });

    BackfillConfig cfg = BackfillConfig.builder().batchSize(7).delayBetweenBatchesMs(0).build();
    try {
      new DatasetFacetsPartitionBackfillJob(jdbi, cfg).run();
    } catch (Exception e) {
      throw new AssertionError("cutover job threw: " + e.getMessage(), e);
    }

    jdbi.useHandle(
        h -> {
          assertThat(
                  h.createQuery(
                          "SELECT EXISTS(SELECT 1 FROM pg_partitioned_table WHERE partrelid ="
                              + " 'dataset_facets'::regclass)")
                      .mapTo(Boolean.class)
                      .one())
              .withFailMessage("dataset_facets should be partitioned")
              .isTrue();
          assertThat(h.createQuery("SELECT count(*) FROM dataset_facets").mapTo(Long.class).one())
              .withFailMessage("all 700 historical + 30 live rows must survive exactly-once")
              .isEqualTo(730L);
          assertThat(
                  h.createQuery("SELECT count(*) FROM dataset_facets WHERE namespace IS NULL")
                      .mapTo(Long.class)
                      .one())
              .withFailMessage("NULL namespaces must be resolved during the copy")
              .isEqualTo(0L);
          assertThat(
                  h.createQuery("SELECT state FROM dataset_facets_partition_state")
                      .mapTo(String.class)
                      .one())
              .isEqualTo("SWAPPED");
          assertThat(
                  h.createQuery("SELECT to_regclass('dataset_facets_p') IS NULL")
                      .mapTo(Boolean.class)
                      .one())
              .withFailMessage("shadow dataset_facets_p should be gone")
              .isTrue();
          assertThat(
                  h.createQuery(
                          "SELECT count(*)=0 FROM pg_trigger WHERE"
                              + " tgname='dataset_facets_mirror_trg'")
                      .mapTo(Boolean.class)
                      .one())
              .withFailMessage("mirror trigger should be dropped")
              .isTrue();
          assertThat(
                  h.createQuery("SELECT to_regclass('dataset_facets_view') IS NOT NULL")
                      .mapTo(Boolean.class)
                      .one())
              .withFailMessage("dataset_facets_view should be recreated")
              .isTrue();
          assertThat(
                  h.createQuery(
                          "SELECT count(*) FROM pg_constraint WHERE"
                              + " conrelid='dataset_facets'::regclass AND contype='f'")
                      .mapTo(Long.class)
                      .one())
              .withFailMessage("all three FKs should be restored on dataset_facets")
              .isEqualTo(3L);
        });
  }
}

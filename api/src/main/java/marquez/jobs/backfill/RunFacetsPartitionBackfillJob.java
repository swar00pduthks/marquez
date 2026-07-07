/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.jobs.backfill;

import java.time.Instant;
import java.util.List;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.jobs.BackfillConfig;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Online cutover for partitioning {@code run_facets} on a large existing database, mirroring what
 * V108 does inline on a small/empty one — but without blocking startup.
 *
 * <p>V108 arms the cutover for a large table: it builds the empty partitioned shadow {@code
 * run_facets_p}, installs an {@code AFTER INSERT} trigger that mirrors every row with {@code
 * created_at >= cutover} into the shadow, and records {@code cutover} in {@code
 * run_facets_partition_state}. This job then, in the background:
 *
 * <ol>
 *   <li><strong>Bulk copy</strong> — copies the historical rows ({@code created_at < cutover}) into
 *       the shadow in ctid-keyset batches, checkpointed in {@code backfill_checkpoints} so a
 *       restart resumes without re-copying. The trigger owns everything at/after the cutover, so
 *       the two sets are disjoint by value: exactly-once, no primary key required.
 *   <li><strong>Verified swap</strong> — once the copy drains, it takes a brief {@code ACCESS
 *       EXCLUSIVE} lock, confirms {@code count(run_facets) == count(run_facets_p)} (both frozen),
 *       and only then atomically renames the shadow into place, restores the FK + view, and drops
 *       the trigger and old table. The old table is the source of truth until this verified swap,
 *       so an interrupted copy or a count mismatch loses nothing — it simply retries.
 * </ol>
 *
 * <p>On a small/empty database V108 already swapped inline (state {@code SWAPPED}); this job then
 * detects the finished state and no-ops.
 */
@Slf4j
public class RunFacetsPartitionBackfillJob implements BackfillJob {

  public static final String VERSION = "RUN_FACETS_PARTITION_V1";

  private final Jdbi jdbi;
  private final BackfillConfig config;

  public RunFacetsPartitionBackfillJob(@NonNull Jdbi jdbi, @NonNull BackfillConfig config) {
    this.jdbi = jdbi;
    this.config = config;
  }

  @Override
  public String version() {
    return VERSION;
  }

  @Override
  public void run() throws Exception {
    if (isCompleted()) {
      log.info("RunFacetsPartitionBackfillJob: already completed — skipping.");
      return;
    }

    // Nothing to do unless V108 armed an online cutover on a large table.
    String state = readState();
    if (!"DUAL_WRITE".equals(state)) {
      log.info(
          "RunFacetsPartitionBackfillJob: state='{}' (not DUAL_WRITE) — run_facets was partitioned"
              + " inline or is not armed; nothing to do.",
          state);
      markCompleted();
      return;
    }
    if (isRunFacetsPartitioned()) {
      log.info("RunFacetsPartitionBackfillJob: run_facets already partitioned — marking done.");
      markCompleted();
      return;
    }

    Instant cutover = readCutover();
    log.info(
        "RunFacetsPartitionBackfillJob: starting online copy of rows created before {}.", cutover);

    long copied = runBulkCopy(cutover);
    log.info("RunFacetsPartitionBackfillJob: bulk copy drained after {} rows this run.", copied);

    if (Thread.currentThread().isInterrupted()) {
      log.info("RunFacetsPartitionBackfillJob: interrupted before swap — will resume on restart.");
      return;
    }

    performVerifiedSwap();
  }

  // ---------------------------------------------------------------------------
  // Bulk copy — ctid keyset, one transaction per batch, checkpointed.
  // ---------------------------------------------------------------------------

  private long runBulkCopy(Instant cutover) throws InterruptedException {
    String lastCtid = readCheckpointCtid();
    long total = 0;

    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("Interrupted during run_facets bulk copy");
      }

      final String from = lastCtid;
      List<String> ctids =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          // Alias the projection: an unaliased `ctid::text` is output-named `ctid`,
                          // and ORDER BY would then bind to that TEXT column (lexical order:
                          // '(0,10)' < '(0,2)') instead of the tid column, corrupting the keyset.
                          """
                          SELECT ctid::text AS ctid_text FROM run_facets
                          WHERE created_at < :cutover AND ctid > :from::tid
                          ORDER BY ctid
                          LIMIT :batchSize
                          """)
                      .bind("cutover", cutover)
                      .bind("from", from)
                      .bind("batchSize", config.getBatchSize())
                      .mapTo(String.class)
                      .list());

      if (ctids.isEmpty()) {
        break;
      }

      final String batchLast = ctids.get(ctids.size() - 1);
      jdbi.useTransaction(
          handle -> {
            handle
                .createUpdate(
                    """
                    INSERT INTO run_facets_p (
                        created_at, run_uuid, lineage_event_time, lineage_event_type,
                        name, facet, namespace)
                    SELECT o.created_at, o.run_uuid, o.lineage_event_time, o.lineage_event_type,
                           o.name, o.facet,
                           COALESCE(o.namespace,
                                    (SELECT namespace_name FROM runs WHERE uuid = o.run_uuid))
                    FROM run_facets o
                    WHERE o.ctid = ANY(:ctids::tid[])
                    """)
                .bindArray("ctids", String.class, ctids)
                .execute();
            saveCheckpointCtid(handle, batchLast);
          });

      lastCtid = batchLast;
      total += ctids.size();
      maybeSleep();
    }
    return total;
  }

  // ---------------------------------------------------------------------------
  // Verified swap — brief ACCESS EXCLUSIVE lock, count parity, atomic rename.
  // ---------------------------------------------------------------------------

  private void performVerifiedSwap() {
    jdbi.useTransaction(
        handle -> {
          // Freeze writers so old + shadow are static while we verify and swap.
          handle.execute("LOCK TABLE run_facets IN ACCESS EXCLUSIVE MODE");

          long oldCount = countOf(handle, "run_facets");
          long shadowCount = countOf(handle, "run_facets_p");
          if (oldCount != shadowCount) {
            // Old table is still the source of truth and fully intact. Do NOT swap; abort the
            // transaction so nothing changes, and leave state=DUAL_WRITE to retry. A persistent
            // mismatch (e.g. a clock-skew boundary row) is resolved by rebuilding the shadow.
            throw new IllegalStateException(
                String.format(
                    "run_facets swap aborted: count mismatch old=%d shadow=%d — not swapping,"
                        + " old table preserved. Will retry.",
                    oldCount, shadowCount));
          }

          handle.execute("DROP VIEW IF EXISTS run_facets_view");
          handle.execute("DROP TRIGGER IF EXISTS run_facets_mirror_trg ON run_facets");
          handle.execute("ALTER TABLE run_facets RENAME TO run_facets_old");
          handle.execute("ALTER TABLE run_facets_p RENAME TO run_facets");
          handle.execute(
              "ALTER TABLE run_facets ADD CONSTRAINT run_facets_run_uuid_fkey"
                  + " FOREIGN KEY (run_uuid) REFERENCES runs (uuid) ON DELETE CASCADE");
          handle.execute(
              "CREATE VIEW run_facets_view AS SELECT created_at, run_uuid, lineage_event_time,"
                  + " lineage_event_type, name, facet FROM run_facets");
          handle.execute("DROP TABLE run_facets_old");
          handle.execute("UPDATE run_facets_partition_state SET state = 'SWAPPED'");
        });
    markCompleted();
    log.info("RunFacetsPartitionBackfillJob: verified swap complete — run_facets is partitioned.");
  }

  private long countOf(Handle handle, String table) {
    return handle.createQuery("SELECT count(*) FROM " + table).mapTo(Long.class).one();
  }

  // ---------------------------------------------------------------------------
  // State + checkpoint helpers.
  // ---------------------------------------------------------------------------

  private String readState() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT state FROM run_facets_partition_state LIMIT 1")
                .mapTo(String.class)
                .findOne()
                .orElse("NONE"));
  }

  private Instant readCutover() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT cutover_at FROM run_facets_partition_state LIMIT 1")
                .mapTo(Instant.class)
                .one());
  }

  private boolean isRunFacetsPartitioned() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT EXISTS(SELECT 1 FROM pg_partitioned_table"
                        + " WHERE partrelid = 'run_facets'::regclass)")
                .mapTo(Boolean.class)
                .one());
  }

  private boolean isCompleted() {
    try {
      return jdbi.withHandle(
          handle ->
              handle
                  .createQuery(
                      "SELECT completed_at IS NOT NULL FROM backfill_checkpoints"
                          + " WHERE version = :version")
                  .bind("version", VERSION)
                  .mapTo(Boolean.class)
                  .findOne()
                  .orElse(false));
    } catch (Exception e) {
      log.warn("RunFacetsPartitionBackfillJob: could not check completed_at — assuming not done.");
      return false;
    }
  }

  private void markCompleted() {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO backfill_checkpoints (version, last_cursor_time, last_run_id, completed_at, updated_at)
                    VALUES (:version, now(), '', now(), now())
                    ON CONFLICT (version) DO UPDATE SET completed_at = now(), updated_at = now()
                    """)
                .bind("version", VERSION)
                .execute());
  }

  private String readCheckpointCtid() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT last_run_id FROM backfill_checkpoints WHERE version = :version")
                .bind("version", VERSION)
                .mapTo(String.class)
                .findOne()
                .filter(s -> !s.isEmpty())
                .orElse("(0,0)"));
  }

  private void saveCheckpointCtid(Handle handle, String lastCtid) {
    handle
        .createUpdate(
            """
            INSERT INTO backfill_checkpoints (version, last_cursor_time, last_run_id, updated_at)
            VALUES (:version, '1970-01-01 00:00:00+00', :lastCtid, now())
            ON CONFLICT (version) DO UPDATE SET last_run_id = EXCLUDED.last_run_id, updated_at = now()
            """)
        .bind("version", VERSION)
        .bind("lastCtid", lastCtid)
        .execute();
  }

  private void maybeSleep() throws InterruptedException {
    long delay = config.getDelayBetweenBatchesMs();
    if (delay > 0) {
      Thread.sleep(delay);
    }
  }
}

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
 * Shared online cutover for converting a large, keyless, INSERT-only table into a partitioned one
 * without blocking startup. The size-branched migration (e.g. V108/V111) arms the cutover on a
 * large table: it builds the empty partitioned shadow, installs an {@code AFTER INSERT} trigger
 * that mirrors rows with {@code boundaryColumn >= cutover} into the shadow, and records {@code
 * cutover} + {@code state='DUAL_WRITE'} in a per-table state marker. A concrete subclass of this
 * job then, in the background:
 *
 * <ol>
 *   <li><strong>Bulk copy</strong> — copies history ({@code boundaryColumn < cutover}) into the
 *       shadow in ctid-keyset batches, checkpointed in {@code backfill_checkpoints}. The trigger
 *       owns everything at/after the cutover, so the two sets are disjoint by value: exactly-once,
 *       no primary key required.
 *   <li><strong>Verified swap</strong> — takes a brief {@code ACCESS EXCLUSIVE} lock, confirms
 *       {@code count(table) == count(shadow)} (both frozen), and only then atomically renames the
 *       shadow into place, restores dependents (FKs, view), and drops the trigger and old table.
 *       The old table is the source of truth until this verified swap, so an interrupted copy or a
 *       count mismatch loses nothing — it aborts and retries.
 * </ol>
 *
 * <p>On a small/empty database the migration already swapped inline (state {@code SWAPPED}); this
 * job detects that and no-ops. Subclasses supply only the table-specific SQL fragments.
 */
@Slf4j
public abstract class AbstractPartitionCutoverBackfillJob implements BackfillJob {

  protected final Jdbi jdbi;
  protected final BackfillConfig config;

  protected AbstractPartitionCutoverBackfillJob(
      @NonNull Jdbi jdbi, @NonNull BackfillConfig config) {
    this.jdbi = jdbi;
    this.config = config;
  }

  // --- per-table specification -------------------------------------------------

  /** The live table being partitioned, e.g. {@code run_facets}. */
  protected abstract String table();

  /** The empty partitioned shadow the migration created, e.g. {@code run_facets_p}. */
  protected abstract String shadow();

  /** Single-row cutover state marker table, e.g. {@code run_facets_partition_state}. */
  protected abstract String stateTable();

  /** The dual-write trigger name, dropped at swap, e.g. {@code run_facets_mirror_trg}. */
  protected abstract String triggerName();

  /** Write-time column defining the copy/trigger boundary, e.g. {@code created_at}. */
  protected abstract String boundaryColumn();

  /**
   * Predicate (over the live table, binding {@code :cutover}) selecting the rows the backfill owns
   * — the complement of what the {@code >= cutover} trigger mirrors. Default {@code col <
   * :cutover}; override to treat pre-existing NULLs as historical when the boundary column is
   * nullable.
   */
  protected String copyBoundaryPredicate() {
    return boundaryColumn() + " < :cutover";
  }

  /**
   * Hook run after the swap transaction commits (e.g. refresh a dependent matview). No-op default.
   */
  protected void afterSwapCommitted() {}

  /** Column list for the shadow INSERT (matching {@link #selectExpr()}). */
  protected abstract String insertColumns();

  /** SELECT expression over alias {@code o} for the copy (resolves namespace, etc.). */
  protected abstract String selectExpr();

  /** DDL to run before the rename (e.g. drop the dependent view that pins the old table). */
  protected abstract List<String> preSwapDdl();

  /** DDL to run after the rename (re-add FKs, recreate the view). */
  protected abstract List<String> postSwapDdl();

  // --- orchestration -----------------------------------------------------------

  @Override
  public void run() throws Exception {
    if (isCompleted()) {
      log.info("{}: already completed — skipping.", version());
      return;
    }

    String state = readState();
    if (!"DUAL_WRITE".equals(state)) {
      log.info(
          "{}: state='{}' (not DUAL_WRITE) — swapped inline or not armed; nothing to do.",
          version(),
          state);
      markCompleted();
      return;
    }
    if (isTablePartitioned()) {
      log.info("{}: {} already partitioned — marking done.", version(), table());
      markCompleted();
      return;
    }

    Instant cutover = readCutover();
    log.info("{}: starting online copy of {} rows created before {}.", version(), table(), cutover);

    long copied = runBulkCopy(cutover);
    log.info("{}: bulk copy drained after {} rows this run.", version(), copied);

    if (Thread.currentThread().isInterrupted()) {
      log.info("{}: interrupted before swap — will resume on restart.", version());
      return;
    }

    performVerifiedSwap();
  }

  private long runBulkCopy(Instant cutover) throws InterruptedException {
    String lastCtid = readCheckpointCtid();
    long total = 0;

    // Alias the projection: an unaliased `ctid::text` is output-named `ctid`, and ORDER BY would
    // then bind to that TEXT column (lexical order: '(0,10)' < '(0,2)') instead of the tid column,
    // corrupting the keyset.
    final String selectCtids =
        "SELECT ctid::text AS ctid_text FROM "
            + table()
            + " WHERE ("
            + copyBoundaryPredicate()
            + ") AND ctid > :from::tid"
            + " ORDER BY ctid LIMIT :batchSize";
    final String copyBatch =
        "INSERT INTO "
            + shadow()
            + " ("
            + insertColumns()
            + ") SELECT "
            + selectExpr()
            + " FROM "
            + table()
            + " o WHERE o.ctid = ANY(:ctids::tid[])";

    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("Interrupted during " + table() + " bulk copy");
      }

      final String from = lastCtid;
      List<String> ctids =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(selectCtids)
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
            handle.createUpdate(copyBatch).bindArray("ctids", String.class, ctids).execute();
            saveCheckpointCtid(handle, batchLast);
          });

      lastCtid = batchLast;
      total += ctids.size();
      maybeSleep();
    }
    return total;
  }

  private void performVerifiedSwap() {
    jdbi.useTransaction(
        handle -> {
          handle.execute("LOCK TABLE " + table() + " IN ACCESS EXCLUSIVE MODE");

          long oldCount = countOf(handle, table());
          long shadowCount = countOf(handle, shadow());
          if (oldCount != shadowCount) {
            // Old table is still intact. Do NOT swap; abort so nothing changes and leave
            // state=DUAL_WRITE to retry. A persistent mismatch is resolved by rebuilding the
            // shadow.
            throw new IllegalStateException(
                String.format(
                    "%s swap aborted: count mismatch old=%d shadow=%d — not swapping, old table"
                        + " preserved. Will retry.",
                    table(), oldCount, shadowCount));
          }

          for (String ddl : preSwapDdl()) {
            handle.execute(ddl);
          }
          handle.execute("DROP TRIGGER IF EXISTS " + triggerName() + " ON " + table());
          handle.execute("ALTER TABLE " + table() + " RENAME TO " + table() + "_old");
          handle.execute("ALTER TABLE " + shadow() + " RENAME TO " + table());
          for (String ddl : postSwapDdl()) {
            handle.execute(ddl);
          }
          handle.execute("DROP TABLE " + table() + "_old");
          handle.execute("UPDATE " + stateTable() + " SET state = 'SWAPPED'");
        });
    afterSwapCommitted();
    markCompleted();
    log.info("{}: verified swap complete — {} is partitioned.", version(), table());
  }

  private long countOf(Handle handle, String t) {
    return handle.createQuery("SELECT count(*) FROM " + t).mapTo(Long.class).one();
  }

  // --- state + checkpoint helpers ---------------------------------------------

  private String readState() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT state FROM " + stateTable() + " LIMIT 1")
                .mapTo(String.class)
                .findOne()
                .orElse("NONE"));
  }

  private Instant readCutover() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT cutover_at FROM " + stateTable() + " LIMIT 1")
                .mapTo(Instant.class)
                .one());
  }

  private boolean isTablePartitioned() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT EXISTS(SELECT 1 FROM pg_partitioned_table WHERE partrelid ="
                        + " to_regclass(:t))")
                .bind("t", table())
                .mapTo(Boolean.class)
                .one());
  }

  private boolean isCompleted() {
    try {
      return jdbi.withHandle(
          handle ->
              handle
                  .createQuery(
                      "SELECT completed_at IS NOT NULL FROM backfill_checkpoints WHERE version ="
                          + " :version")
                  .bind("version", version())
                  .mapTo(Boolean.class)
                  .findOne()
                  .orElse(false));
    } catch (Exception e) {
      log.warn("{}: could not check completed_at — assuming not done.", version());
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
                .bind("version", version())
                .execute());
  }

  private String readCheckpointCtid() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT last_run_id FROM backfill_checkpoints WHERE version = :version")
                .bind("version", version())
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
        .bind("version", version())
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

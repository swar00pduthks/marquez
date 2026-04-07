/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.jobs.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.common.Utils;
import marquez.jobs.BackfillConfig;
import marquez.service.models.LineageEvent;
import marquez.v3.db.GraphDao;
import marquez.v3.db.GraphWriter;
import org.jdbi.v3.core.Jdbi;

/**
 * Backfills the Apache AGE property graph from raw OpenLineage events stored in the relational
 * {@code lineage_events} table.
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li>Read checkpoint: find the last {@code event_time} successfully committed for version {@code
 *       GRAPH_V1} in the {@code backfill_checkpoints} table. Default: epoch (process everything).
 *   <li>Page through {@code lineage_events} in ascending {@code event_time} order, {@code
 *       batchSize} rows per page.
 *   <li>For each row, deserialize the {@code event} JSONB column into a {@link LineageEvent} and
 *       call {@link GraphWriter#writeEvent}.
 *   <li>After each batch, commit the checkpoint ({@code event_time} of the last processed row).
 *   <li>Repeat until no more rows are returned.
 * </ol>
 *
 * <h2>Performance</h2>
 *
 * <ul>
 *   <li>Uses a keyset cursor on {@code (event_time, run_id)} to avoid OFFSET scans on a
 *       multi-billion-row table.
 *   <li>Each batch runs in a single JDBI transaction so AGE writes are atomic with the checkpoint
 *       update.
 *   <li>A configurable sleep between batches ({@link BackfillConfig#getDelayBetweenBatchesMs()})
 *       prevents the backfill from monopolising DB connections needed by live ingestion.
 * </ul>
 *
 * <h2>Idempotency</h2>
 *
 * All AGE writes use Cypher {@code MERGE}, so re-processing the same event is a no-op. Re-running
 * this backfill after a partial failure is safe.
 */
@Slf4j
public class GraphV1BackfillJob implements BackfillJob {

  public static final String VERSION = "GRAPH_V1";

  private static final ObjectMapper MAPPER = Utils.getMapper();

  private final Jdbi jdbi;
  private final GraphWriter graphWriter;
  private final BackfillConfig config;

  public GraphV1BackfillJob(
      @NonNull Jdbi jdbi, @NonNull GraphWriter graphWriter, @NonNull BackfillConfig config) {
    this.jdbi = jdbi;
    this.graphWriter = graphWriter;
    this.config = config;
  }

  @Override
  public String version() {
    return VERSION;
  }

  @Override
  public void run() throws Exception {
    if (isCompleted()) {
      log.info("GraphV1BackfillJob: already completed — skipping.");
      return;
    }

    log.info("GraphV1BackfillJob: starting graph backfill from lineage_events table.");

    // Read resumption cursor from checkpoint table
    Instant[] cursor = {readCheckpoint()};
    String[] lastRunId = {""};
    long[] totalProcessed = {0L};
    long[] totalFailed = {0L};

    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        log.info("GraphV1BackfillJob: interrupted — stopping at cursor {}.", cursor[0]);
        break;
      }

      final Instant cursorSnapshot = cursor[0];
      final String lastRunIdSnapshot = lastRunId[0];

      // Load one batch using keyset pagination on (event_time, run_id)
      List<Map<String, Object>> batch =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          """
              SELECT event_time, run_uuid AS run_id, event
              FROM lineage_events
              WHERE (event_time > :cursorTime)
                 OR (event_time = :cursorTime AND run_uuid::text > :lastRunId)
              ORDER BY event_time ASC, run_uuid ASC
              LIMIT :batchSize
              """)
                      .bind("cursorTime", cursorSnapshot)
                      .bind("lastRunId", lastRunIdSnapshot)
                      .bind("batchSize", config.getBatchSize())
                      .mapToMap()
                      .list());

      if (batch.isEmpty()) {
        log.info(
            "GraphV1BackfillJob: no more rows to process. Total processed: {}, failed: {}."
                + " Marking as completed.",
            totalProcessed[0],
            totalFailed[0]);
        markCompleted();
        break;
      }

      log.debug(
          "GraphV1BackfillJob: processing batch of {} events starting after cursor {}.",
          batch.size(),
          cursorSnapshot);

      // Process the batch in a single transaction so AGE writes + checkpoint commit together
      final List<Map<String, Object>> batchFinal = batch;
      jdbi.useTransaction(
          handle -> {
            // Initialize AGE session on this connection
            GraphDao.initAgeSession(handle.getConnection());

            Instant batchMaxTime = cursorSnapshot;
            String batchLastRunId = lastRunIdSnapshot;
            int batchProcessed = 0;
            int batchFailed = 0;

            for (Map<String, Object> row : batchFinal) {
              String eventJson = null;
              try {
                Object eventObj = row.get("event");
                eventJson = eventObj == null ? null : eventObj.toString();
                if (eventJson == null || eventJson.isBlank()) {
                  continue;
                }

                LineageEvent event = MAPPER.readValue(eventJson, LineageEvent.class);
                if (event == null || event.getJob() == null || event.getRun() == null) {
                  log.warn(
                      "GraphV1BackfillJob: skipping malformed event for run_id={}",
                      row.get("run_id"));
                  continue;
                }

                graphWriter.writeEvent(handle, event);
                batchProcessed++;

                // Track keyset cursor
                Object et = row.get("event_time");
                if (et instanceof Instant) {
                  batchMaxTime = (Instant) et;
                } else if (et instanceof java.sql.Timestamp) {
                  batchMaxTime = ((java.sql.Timestamp) et).toInstant();
                }
                Object rid = row.get("run_id");
                if (rid != null) {
                  batchLastRunId = rid.toString();
                }

              } catch (Exception e) {
                batchFailed++;
                log.warn(
                    "GraphV1BackfillJob: failed to write event for run_id={}: {}",
                    row.get("run_id"),
                    e.getMessage());
              }
            }

            // Commit checkpoint for this batch
            saveCheckpoint(handle, batchMaxTime, batchLastRunId);

            // Update mutable state for next iteration
            cursor[0] = batchMaxTime;
            lastRunId[0] = batchLastRunId;
            totalProcessed[0] += batchProcessed;
            totalFailed[0] += batchFailed;

            log.info(
                "GraphV1BackfillJob: batch done — processed={}, failed={}, cursor={}, total={}",
                batchProcessed,
                batchFailed,
                batchMaxTime,
                totalProcessed[0]);
          });

      // Yield between batches to avoid starving live ingestion connections
      long delay = config.getDelayBetweenBatchesMs();
      if (delay > 0) {
        Thread.sleep(delay);
      }
    }

    log.info(
        "GraphV1BackfillJob: finished. totalProcessed={}, totalFailed={}.",
        totalProcessed[0],
        totalFailed[0]);
  }

  // ---------------------------------------------------------------------------
  // Checkpoint helpers
  // ---------------------------------------------------------------------------

  private boolean isCompleted() {
    try {
      return jdbi.withHandle(
          handle ->
              handle
                  .createQuery(
                      """
                      SELECT completed_at IS NOT NULL
                      FROM backfill_checkpoints
                      WHERE version = :version
                      """)
                  .bind("version", VERSION)
                  .mapTo(Boolean.class)
                  .findOne()
                  .orElse(false));
    } catch (Exception e) {
      log.warn(
          "GraphV1BackfillJob: could not check completed_at — assuming not completed. Error: {}",
          e.getMessage());
      return false;
    }
  }

  private void markCompleted() {
    try {
      jdbi.useHandle(
          handle ->
              handle
                  .createUpdate(
                      """
                      INSERT INTO backfill_checkpoints (version, last_cursor_time, last_run_id, completed_at, updated_at)
                      VALUES (:version, now(), '', now(), now())
                      ON CONFLICT (version) DO UPDATE SET
                        completed_at = now(),
                        updated_at   = now()
                      """)
                  .bind("version", VERSION)
                  .execute());
      log.info("GraphV1BackfillJob: marked as completed in backfill_checkpoints.");
    } catch (Exception e) {
      log.warn(
          "GraphV1BackfillJob: could not write completed_at — will re-run on next restart. Error: {}",
          e.getMessage());
    }
  }

  private Instant readCheckpoint() {
    try {
      return jdbi.withHandle(
          handle ->
              handle
                  .createQuery(
                      """
                      SELECT last_cursor_time
                      FROM backfill_checkpoints
                      WHERE version = :version
                      """)
                  .bind("version", VERSION)
                  .mapTo(Instant.class)
                  .findOne()
                  .orElse(Instant.EPOCH));
    } catch (Exception e) {
      log.warn(
          "GraphV1BackfillJob: could not read checkpoint (table may not exist yet) — starting from"
              + " epoch. Error: {}",
          e.getMessage());
      return Instant.EPOCH;
    }
  }

  private void saveCheckpoint(
      org.jdbi.v3.core.Handle handle, Instant cursorTime, String lastRunId) {
    handle
        .createUpdate(
            """
            INSERT INTO backfill_checkpoints (version, last_cursor_time, last_run_id, updated_at)
            VALUES (:version, :cursorTime, :lastRunId, now())
            ON CONFLICT (version) DO UPDATE SET
              last_cursor_time = EXCLUDED.last_cursor_time,
              last_run_id      = EXCLUDED.last_run_id,
              updated_at       = EXCLUDED.updated_at
            """)
        .bind("version", VERSION)
        .bind("cursorTime", cursorTime)
        .bind("lastRunId", lastRunId)
        .execute();
  }
}

/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.jobs.backfill;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.jobs.BackfillConfig;
import marquez.service.PartitionManagementService;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Async backfill for all relational denormalized tables, mirroring the work originally done by
 * Flyway Java migrations V86, V90, V92, V93, and V94 — but running in the background without
 * blocking server startup.
 *
 * <h2>Two phases</h2>
 *
 * <ol>
 *   <li><strong>Namespace phase</strong> ({@code DENORM_V1_NAMESPACE}) — iterates every namespace
 *       in keyset order and upserts the full current-column set into {@code dataset_denormalized},
 *       {@code dataset_version_denormalized}, and {@code job_denormalized}, incorporating all
 *       columns added by V90, V92, and V93.
 *   <li><strong>Run phase</strong> ({@code DENORM_V1_RUN}) — iterates completed runs in {@code
 *       (created_at, uuid)} keyset order and upserts {@code run_lineage_denormalized} and {@code
 *       run_parent_lineage_denormalized} with the full V86 + V94 column set (including {@code
 *       input_uuids} / {@code output_uuids}).
 * </ol>
 *
 * <h2>Billion-row design</h2>
 *
 * <ul>
 *   <li>Keyset pagination on every loop — no OFFSET scans on large tables.
 *   <li>One checkpoint row per phase in {@code backfill_checkpoints}; restart picks up exactly
 *       where it left off.
 *   <li>All writes use {@code INSERT … ON CONFLICT … DO UPDATE} (upsert), so re-running is safe.
 *   <li>Configurable {@code batchSize} and {@code delayBetweenBatchesMs} let you tune DB pressure.
 * </ul>
 */
@Slf4j
public class DenormV1BackfillJob implements BackfillJob {

  public static final String VERSION = "DENORM_V1";

  // Separate checkpoint keys per phase so each phase is independently resumable
  private static final String CP_NAMESPACE = "DENORM_V1_NAMESPACE";
  private static final String CP_RUN = "DENORM_V1_RUN";

  private final Jdbi jdbi;
  private final BackfillConfig config;
  private final PartitionManagementService partitionManagementService;

  public DenormV1BackfillJob(@NonNull Jdbi jdbi, @NonNull BackfillConfig config) {
    this.jdbi = jdbi;
    this.config = config;
    // Use 10 months back, 12 ahead — same defaults as MarquezApp
    this.partitionManagementService = new PartitionManagementService(jdbi, 10, 12);
  }

  @Override
  public String version() {
    return VERSION;
  }

  @Override
  public void run() throws Exception {
    log.info("DenormV1BackfillJob: starting.");

    runNamespacePhase();

    if (!Thread.currentThread().isInterrupted()) {
      runRunPhase();
    }

    log.info("DenormV1BackfillJob: finished.");
  }

  // ---------------------------------------------------------------------------
  // Phase 1: entity denorm (dataset_denormalized, dataset_version_denormalized, job_denormalized)
  // ---------------------------------------------------------------------------

  private void runNamespacePhase() throws InterruptedException {
    log.info("DenormV1BackfillJob [namespace phase]: starting.");

    String lastNs = readCheckpointRunId(CP_NAMESPACE); // reuse last_run_id for namespace UUID
    long totalNs = 0;

    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        log.info("DenormV1BackfillJob [namespace phase]: interrupted at lastNs={}.", lastNs);
        throw new InterruptedException("Interrupted during namespace phase");
      }

      final String lastNsSnapshot = lastNs;
      List<UUID> batch =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          """
              SELECT uuid FROM namespaces
              WHERE uuid > :lastUuid::uuid
              ORDER BY uuid ASC
              LIMIT :batchSize
              """)
                      .bind(
                          "lastUuid",
                          lastNsSnapshot.isEmpty()
                              ? "00000000-0000-0000-0000-000000000000"
                              : lastNsSnapshot)
                      .bind("batchSize", config.getBatchSize())
                      .mapTo(UUID.class)
                      .list());

      if (batch.isEmpty()) break;

      final String[] nextLast = {lastNs};
      jdbi.useTransaction(
          handle -> {
            for (UUID nsUuid : batch) {
              try {
                upsertDatasetsDenormalized(handle, nsUuid);
                upsertDatasetVersionsDenormalized(handle, nsUuid);
                upsertJobsDenormalized(handle, nsUuid);
                nextLast[0] = nsUuid.toString();
              } catch (Exception e) {
                log.warn(
                    "DenormV1BackfillJob [namespace phase]: failed for namespace {}: {}",
                    nsUuid,
                    e.getMessage());
              }
            }
            saveCheckpointRunId(handle, CP_NAMESPACE, nextLast[0]);
          });

      lastNs = nextLast[0];
      totalNs += batch.size();
      log.info(
          "DenormV1BackfillJob [namespace phase]: processed {} namespaces total, lastNs={}.",
          totalNs,
          lastNs);

      maybeSleep();
    }

    log.info(
        "DenormV1BackfillJob [namespace phase]: done. Total namespaces processed: {}.", totalNs);
  }

  /**
   * Upserts dataset_denormalized with the full column set from V88 + V92 (namespace_name,
   * source_name, last_modified_at, is_deleted).
   */
  private void upsertDatasetsDenormalized(Handle handle, UUID namespaceUuid) {
    // Remove stale rows that conflict on UNIQUE (namespace_uuid, source_uuid, name, physical_name)
    // but have a different uuid — e.g. dataset was re-created with a new UUID.
    handle
        .createUpdate(
            """
        DELETE FROM dataset_denormalized d
        USING datasets ds
        WHERE ds.namespace_uuid = :namespaceUuid
          AND d.namespace_uuid  = ds.namespace_uuid
          AND d.source_uuid     = ds.source_uuid
          AND d.name            = ds.name
          AND d.physical_name   = ds.physical_name
          AND d.uuid           != ds.uuid
        """)
        .bind("namespaceUuid", namespaceUuid)
        .execute();

    handle
        .createUpdate(
            """
        INSERT INTO dataset_denormalized (
            uuid, type, created_at, updated_at, namespace_uuid, source_uuid, name,
            physical_name, description, current_version_uuid, tags, schema_location,
            lifecycle_state, namespace_name, source_name, last_modified_at, is_deleted
        )
        SELECT
            d.uuid, d.type, d.created_at, d.updated_at, d.namespace_uuid, d.source_uuid,
            d.name, d.physical_name, d.description, d.current_version_uuid,
            (SELECT ARRAY_AGG(t.name) FROM tags t
             INNER JOIN datasets_tag_mapping m ON m.tag_uuid = t.uuid
             WHERE m.dataset_uuid = d.uuid),
            sv.schema_location, dv.lifecycle_state,
            n.name AS namespace_name,
            s.name AS source_name,
            dv.created_at AS last_modified_at,
            d.is_deleted
        FROM datasets d
        LEFT JOIN dataset_versions dv ON d.current_version_uuid = dv.uuid
        LEFT JOIN stream_versions sv ON sv.dataset_version_uuid = dv.uuid
        LEFT JOIN namespaces n ON n.uuid = d.namespace_uuid
        LEFT JOIN sources s ON s.uuid = d.source_uuid
        WHERE d.namespace_uuid = :namespaceUuid
        ON CONFLICT (uuid, namespace_uuid) DO UPDATE SET
            updated_at            = EXCLUDED.updated_at,
            current_version_uuid  = EXCLUDED.current_version_uuid,
            tags                  = EXCLUDED.tags,
            schema_location       = EXCLUDED.schema_location,
            lifecycle_state       = EXCLUDED.lifecycle_state,
            description           = EXCLUDED.description,
            namespace_name        = EXCLUDED.namespace_name,
            source_name           = EXCLUDED.source_name,
            last_modified_at      = EXCLUDED.last_modified_at,
            is_deleted            = EXCLUDED.is_deleted
        """)
        .bind("namespaceUuid", namespaceUuid)
        .execute();
  }

  /** Upserts dataset_version_denormalized (columns unchanged since V88). */
  private void upsertDatasetVersionsDenormalized(Handle handle, UUID namespaceUuid) {
    // Remove stale rows that conflict on UNIQUE (dataset_uuid, version, namespace_uuid)
    // but have a different uuid.
    handle
        .createUpdate(
            """
        DELETE FROM dataset_version_denormalized dv
        USING dataset_versions dvs
        INNER JOIN datasets ds ON ds.uuid = dvs.dataset_uuid
        WHERE ds.namespace_uuid  = :namespaceUuid
          AND dv.dataset_uuid    = dvs.dataset_uuid
          AND dv.version         = dvs.version
          AND dv.namespace_uuid  = ds.namespace_uuid
          AND dv.uuid           != dvs.uuid
        """)
        .bind("namespaceUuid", namespaceUuid)
        .execute();

    handle
        .createUpdate(
            """
        INSERT INTO dataset_version_denormalized (
            uuid, dataset_uuid, namespace_uuid, version, created_at, fields,
            schema_location, lifecycle_state
        )
        SELECT
            dv.uuid, dv.dataset_uuid, d.namespace_uuid, dv.version, dv.created_at, dv.fields,
            sv.schema_location, dv.lifecycle_state
        FROM dataset_versions dv
        INNER JOIN datasets d ON d.uuid = dv.dataset_uuid
        LEFT JOIN stream_versions sv ON sv.dataset_version_uuid = dv.uuid
        WHERE d.namespace_uuid = :namespaceUuid
        ON CONFLICT (uuid, namespace_uuid) DO NOTHING
        """)
        .bind("namespaceUuid", namespaceUuid)
        .execute();
  }

  /**
   * Upserts job_denormalized with the full column set from V88 + V92 + V93 (namespace_name,
   * simple_name, parent_job_uuid, parent_job_name, current_location, current_inputs, input_uuids,
   * output_uuids).
   */
  private void upsertJobsDenormalized(Handle handle, UUID namespaceUuid) {
    // Remove stale rows that conflict on UNIQUE (namespace_uuid, name) but have a different uuid
    // — e.g. job was re-created with a new UUID.
    handle
        .createUpdate(
            """
        DELETE FROM job_denormalized d
        USING jobs j
        WHERE j.namespace_uuid = :namespaceUuid
          AND d.namespace_uuid = j.namespace_uuid
          AND d.name           = j.name
          AND d.uuid          != j.uuid
        """)
        .bind("namespaceUuid", namespaceUuid)
        .execute();

    handle
        .createUpdate(
            """
        INSERT INTO job_denormalized (
            uuid, type, created_at, updated_at, namespace_uuid, name,
            description, current_version_uuid, tags,
            namespace_name, simple_name, parent_job_uuid, parent_job_name,
            current_location, current_inputs, input_uuids, output_uuids
        )
        SELECT
            j.uuid, j.type, j.created_at, j.updated_at, j.namespace_uuid, j.name,
            j.description, j.current_version_uuid,
            (SELECT ARRAY_AGG(t.name) FROM tags t
             INNER JOIN jobs_tag_mapping m ON m.tag_uuid = t.uuid
             WHERE m.job_uuid = j.uuid),
            n.name AS namespace_name,
            CASE WHEN j.name LIKE '%/%' THEN SPLIT_PART(j.name, '/', -1) ELSE j.name END AS simple_name,
            pj.uuid AS parent_job_uuid,
            pj.name AS parent_job_name,
            jv.location AS current_location,
            j.current_inputs,
            COALESCE(io_agg.input_uuids,  ARRAY[]::uuid[]) AS input_uuids,
            COALESCE(io_agg.output_uuids, ARRAY[]::uuid[]) AS output_uuids
        FROM jobs j
        LEFT JOIN namespaces n ON n.uuid = j.namespace_uuid
        LEFT JOIN jobs pj ON pj.uuid = j.parent_job_uuid
        LEFT JOIN job_versions jv ON jv.uuid = j.current_version_uuid
        LEFT JOIN (
            SELECT
                io.job_uuid,
                ARRAY_AGG(DISTINCT io.dataset_uuid) FILTER (WHERE io.io_type = 'INPUT'  AND io.dataset_uuid IS NOT NULL) AS input_uuids,
                ARRAY_AGG(DISTINCT io.dataset_uuid) FILTER (WHERE io.io_type = 'OUTPUT' AND io.dataset_uuid IS NOT NULL) AS output_uuids
            FROM job_versions_io_mapping io
            WHERE io.is_current_job_version = TRUE
            GROUP BY io.job_uuid
        ) io_agg ON io_agg.job_uuid = j.uuid
        WHERE j.namespace_uuid = :namespaceUuid
        ON CONFLICT (uuid, namespace_uuid) DO UPDATE SET
            updated_at           = EXCLUDED.updated_at,
            current_version_uuid = EXCLUDED.current_version_uuid,
            tags                 = EXCLUDED.tags,
            description          = EXCLUDED.description,
            namespace_name       = EXCLUDED.namespace_name,
            simple_name          = EXCLUDED.simple_name,
            parent_job_uuid      = EXCLUDED.parent_job_uuid,
            parent_job_name      = EXCLUDED.parent_job_name,
            current_location     = EXCLUDED.current_location,
            current_inputs       = EXCLUDED.current_inputs,
            input_uuids          = EXCLUDED.input_uuids,
            output_uuids         = EXCLUDED.output_uuids
        """)
        .bind("namespaceUuid", namespaceUuid)
        .execute();
  }

  // ---------------------------------------------------------------------------
  // Phase 2: run lineage denorm (run_lineage_denormalized, run_parent_lineage_denormalized)
  // ---------------------------------------------------------------------------

  private void runRunPhase() throws InterruptedException {
    log.info("DenormV1BackfillJob [run phase]: starting.");

    Instant lastTime = readCheckpointCursorTime(CP_RUN);
    String lastUuid = readCheckpointRunId(CP_RUN);
    long totalRuns = 0;

    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        log.info(
            "DenormV1BackfillJob [run phase]: interrupted at lastTime={}, lastUuid={}.",
            lastTime,
            lastUuid);
        throw new InterruptedException("Interrupted during run phase");
      }

      final Instant lastTimeSnap = lastTime;
      final String lastUuidSnap = lastUuid;

      // Keyset paginate completed runs by (created_at, uuid)
      List<UUID> batch =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          """
              SELECT uuid FROM runs
              WHERE ended_at IS NOT NULL
                AND (created_at > :lastTime
                     OR (created_at = :lastTime AND uuid::text > :lastUuid))
              ORDER BY created_at ASC, uuid ASC
              LIMIT :batchSize
              """)
                      .bind("lastTime", lastTimeSnap)
                      .bind(
                          "lastUuid",
                          lastUuidSnap.isEmpty()
                              ? "00000000-0000-0000-0000-000000000000"
                              : lastUuidSnap)
                      .bind("batchSize", config.getBatchSize())
                      .mapTo(UUID.class)
                      .list());

      if (batch.isEmpty()) break;

      final Instant[] nextTime = {lastTime};
      final String[] nextUuid = {lastUuid};

      // Process each run individually (matches V86 behaviour for partition safety)
      for (UUID runUuid : batch) {
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException("Interrupted during run phase batch");
        }
        try {
          jdbi.useTransaction(
              handle -> {
                ensurePartitionExists(handle, runUuid);
                upsertRunLineageDenormalized(handle, runUuid);
                if (isParentRun(handle, runUuid)) {
                  upsertRunParentLineageDenormalized(handle, runUuid);
                }
                // Read run created_at for checkpoint
                Instant createdAt =
                    handle
                        .createQuery("SELECT created_at FROM runs WHERE uuid = :uuid")
                        .bind("uuid", runUuid)
                        .mapTo(Instant.class)
                        .findOne()
                        .orElse(nextTime[0]);
                nextTime[0] = createdAt;
                nextUuid[0] = runUuid.toString();
              });
        } catch (Exception e) {
          log.warn(
              "DenormV1BackfillJob [run phase]: failed for run {}: {}", runUuid, e.getMessage());
        }
      }

      // Commit checkpoint after entire batch
      jdbi.useHandle(handle -> saveCheckpoint(handle, CP_RUN, nextTime[0], nextUuid[0]));

      lastTime = nextTime[0];
      lastUuid = nextUuid[0];
      totalRuns += batch.size();
      log.info(
          "DenormV1BackfillJob [run phase]: processed {} runs total, lastTime={}, lastUuid={}.",
          totalRuns,
          lastTime,
          lastUuid);

      maybeSleep();
    }

    log.info("DenormV1BackfillJob [run phase]: done. Total runs processed: {}.", totalRuns);
  }

  private void ensurePartitionExists(Handle handle, UUID runUuid) {
    handle
        .createQuery(
            "SELECT DATE(ended_at)::text FROM runs WHERE uuid = :uuid AND ended_at IS NOT NULL")
        .bind("uuid", runUuid)
        .mapTo(String.class)
        .findOne()
        .ifPresent(
            dateStr -> {
              try {
                partitionManagementService.ensurePartitionExists(
                    java.time.LocalDate.parse(dateStr));
              } catch (Exception e) {
                log.warn(
                    "DenormV1BackfillJob: could not ensure partition for date {}: {}",
                    dateStr,
                    e.getMessage());
              }
            });
  }

  /**
   * Populates run_lineage_denormalized for a single run using delete-then-insert.
   *
   * <p>The table PK is {@code (id, run_date)} where {@code id = gen_random_uuid()} — there is no
   * unique constraint on {@code run_uuid}, so ON CONFLICT cannot be used. This mirrors V86's {@code
   * deleteExistingRunRecords} + {@code insertRunLineageDenormalized} pattern.
   *
   * <p>Covers the full V82 + V94 column set (including {@code input_uuids} / {@code output_uuids}).
   */
  private void upsertRunLineageDenormalized(Handle handle, UUID runUuid) {
    // Delete any existing rows for this run (idempotency)
    handle
        .createUpdate("DELETE FROM run_lineage_denormalized WHERE run_uuid = :runUuid")
        .bind("runUuid", runUuid)
        .execute();

    handle
        .createUpdate(
            """
        INSERT INTO run_lineage_denormalized (
            run_uuid, namespace_name, job_name, state, created_at, updated_at,
            started_at, ended_at, job_uuid, job_version_uuid,
            input_version_uuid, input_dataset_uuid, output_version_uuid, output_dataset_uuid,
            input_dataset_namespace, input_dataset_name, input_dataset_version,
            input_dataset_version_uuid, output_dataset_namespace, output_dataset_name,
            output_dataset_version, output_dataset_version_uuid,
            uuid, parent_run_uuid, run_date,
            input_uuids, output_uuids
        )
        SELECT DISTINCT
            r.uuid                          AS run_uuid,
            r.namespace_name,
            r.job_name,
            r.current_run_state             AS state,
            r.created_at,
            r.updated_at,
            r.started_at,
            r.ended_at,
            r.job_uuid,
            r.job_version_uuid,
            rim.dataset_version_uuid        AS input_version_uuid,
            dvin.dataset_uuid               AS input_dataset_uuid,
            dvout.uuid                      AS output_version_uuid,
            dvout.dataset_uuid              AS output_dataset_uuid,
            dvin.namespace_name             AS input_dataset_namespace,
            dvin.dataset_name               AS input_dataset_name,
            dvin.version                    AS input_dataset_version,
            dvin.uuid                       AS input_dataset_version_uuid,
            dvout.namespace_name            AS output_dataset_namespace,
            dvout.dataset_name              AS output_dataset_name,
            dvout.version                   AS output_dataset_version,
            dvout.uuid                      AS output_dataset_version_uuid,
            r.uuid                          AS uuid,
            r.parent_run_uuid,
            DATE(r.ended_at)                AS run_date,
            COALESCE(io_agg.input_uuids,  ARRAY[]::uuid[]) AS input_uuids,
            COALESCE(io_agg.output_uuids, ARRAY[]::uuid[]) AS output_uuids
        FROM runs r
        LEFT JOIN runs_input_mapping rim ON rim.run_uuid = r.uuid
        LEFT JOIN dataset_versions dvin ON dvin.uuid = rim.dataset_version_uuid
        LEFT JOIN dataset_versions dvout ON dvout.run_uuid = r.uuid
        LEFT JOIN (
            SELECT
                rim2.run_uuid,
                ARRAY_AGG(DISTINCT dvin2.dataset_uuid) FILTER (WHERE dvin2.dataset_uuid IS NOT NULL) AS input_uuids,
                ARRAY_AGG(DISTINCT dvout2.dataset_uuid) FILTER (WHERE dvout2.dataset_uuid IS NOT NULL) AS output_uuids
            FROM runs_input_mapping rim2
            LEFT JOIN dataset_versions dvin2 ON dvin2.uuid = rim2.dataset_version_uuid
            LEFT JOIN dataset_versions dvout2 ON dvout2.run_uuid = rim2.run_uuid
            WHERE rim2.run_uuid = :runUuid
            GROUP BY rim2.run_uuid
        ) io_agg ON io_agg.run_uuid = r.uuid
        WHERE r.uuid = :runUuid AND r.ended_at IS NOT NULL
        """)
        .bind("runUuid", runUuid)
        .execute();
  }

  /**
   * Populates run_parent_lineage_denormalized for all child runs of a parent run.
   *
   * <p>Same delete-then-insert pattern as {@link #upsertRunLineageDenormalized} — no unique
   * constraint on {@code run_uuid}, so ON CONFLICT cannot be used.
   */
  private void upsertRunParentLineageDenormalized(Handle handle, UUID parentRunUuid) {
    // Delete existing child-run rows for this parent (idempotency)
    handle
        .createUpdate("DELETE FROM run_parent_lineage_denormalized WHERE run_uuid = :parentRunUuid")
        .bind("parentRunUuid", parentRunUuid)
        .execute();

    handle
        .createUpdate(
            """
        INSERT INTO run_parent_lineage_denormalized (
            run_uuid, namespace_name, job_name, state, created_at, updated_at,
            started_at, ended_at, job_uuid, job_version_uuid,
            input_version_uuid, input_dataset_uuid, output_version_uuid, output_dataset_uuid,
            input_dataset_namespace, input_dataset_name, input_dataset_version,
            input_dataset_version_uuid, output_dataset_namespace, output_dataset_name,
            output_dataset_version, output_dataset_version_uuid,
            uuid, parent_run_uuid, run_date,
            input_uuids, output_uuids
        )
        SELECT DISTINCT
            COALESCE(r.parent_run_uuid, r.uuid) AS run_uuid,
            rp.namespace_name,
            rp.job_name,
            rp.current_run_state            AS state,
            rp.created_at,
            rp.updated_at,
            rp.started_at,
            rp.ended_at,
            rp.job_uuid,
            rp.job_version_uuid,
            rim.dataset_version_uuid        AS input_version_uuid,
            dvin.dataset_uuid               AS input_dataset_uuid,
            dvout.uuid                      AS output_version_uuid,
            dvout.dataset_uuid              AS output_dataset_uuid,
            dvin.namespace_name             AS input_dataset_namespace,
            dvin.dataset_name               AS input_dataset_name,
            dvin.version                    AS input_dataset_version,
            dvin.uuid                       AS input_dataset_version_uuid,
            dvout.namespace_name            AS output_dataset_namespace,
            dvout.dataset_name              AS output_dataset_name,
            dvout.version                   AS output_dataset_version,
            dvout.uuid                      AS output_dataset_version_uuid,
            r.uuid                          AS uuid,
            r.parent_run_uuid,
            DATE(rp.ended_at)               AS run_date,
            COALESCE(io_agg.input_uuids,  ARRAY[]::uuid[]) AS input_uuids,
            COALESCE(io_agg.output_uuids, ARRAY[]::uuid[]) AS output_uuids
        FROM runs r
        LEFT JOIN runs_input_mapping rim ON rim.run_uuid = r.uuid
        LEFT JOIN dataset_versions dvin ON dvin.uuid = rim.dataset_version_uuid
        LEFT JOIN dataset_versions dvout ON dvout.run_uuid = r.uuid
        INNER JOIN runs rp ON rp.uuid = r.parent_run_uuid
        LEFT JOIN (
            SELECT
                rim2.run_uuid,
                ARRAY_AGG(DISTINCT dvin2.dataset_uuid) FILTER (WHERE dvin2.dataset_uuid IS NOT NULL) AS input_uuids,
                ARRAY_AGG(DISTINCT dvout2.dataset_uuid) FILTER (WHERE dvout2.dataset_uuid IS NOT NULL) AS output_uuids
            FROM runs_input_mapping rim2
            LEFT JOIN dataset_versions dvin2 ON dvin2.uuid = rim2.dataset_version_uuid
            LEFT JOIN dataset_versions dvout2 ON dvout2.run_uuid = rim2.run_uuid
            GROUP BY rim2.run_uuid
        ) io_agg ON io_agg.run_uuid = r.uuid
        WHERE r.parent_run_uuid = :parentRunUuid AND rp.ended_at IS NOT NULL
        """)
        .bind("parentRunUuid", parentRunUuid)
        .execute();
  }

  private boolean isParentRun(Handle handle, UUID runUuid) {
    Integer count =
        handle
            .createQuery("SELECT COUNT(*) FROM runs WHERE parent_run_uuid = :uuid")
            .bind("uuid", runUuid)
            .mapTo(Integer.class)
            .one();
    return count != null && count > 0;
  }

  // ---------------------------------------------------------------------------
  // Checkpoint helpers — re-use the backfill_checkpoints table created in V99
  // ---------------------------------------------------------------------------

  private Instant readCheckpointCursorTime(String version) {
    try {
      return jdbi.withHandle(
          handle ->
              handle
                  .createQuery(
                      "SELECT last_cursor_time FROM backfill_checkpoints WHERE version = :version")
                  .bind("version", version)
                  .mapTo(Instant.class)
                  .findOne()
                  .orElse(Instant.EPOCH));
    } catch (Exception e) {
      log.warn(
          "DenormV1BackfillJob: could not read checkpoint cursor_time for {} — starting from epoch.",
          version);
      return Instant.EPOCH;
    }
  }

  private String readCheckpointRunId(String version) {
    try {
      return jdbi.withHandle(
          handle ->
              handle
                  .createQuery(
                      "SELECT last_run_id FROM backfill_checkpoints WHERE version = :version")
                  .bind("version", version)
                  .mapTo(String.class)
                  .findOne()
                  .orElse(""));
    } catch (Exception e) {
      log.warn(
          "DenormV1BackfillJob: could not read checkpoint run_id for {} — starting from beginning.",
          version);
      return "";
    }
  }

  private void saveCheckpointRunId(Handle handle, String version, String lastRunId) {
    saveCheckpoint(handle, version, Instant.EPOCH, lastRunId);
  }

  private void saveCheckpoint(Handle handle, String version, Instant cursorTime, String lastRunId) {
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
        .bind("version", version)
        .bind("cursorTime", cursorTime)
        .bind("lastRunId", lastRunId)
        .execute();
  }

  private void maybeSleep() throws InterruptedException {
    long delay = config.getDelayBetweenBatchesMs();
    if (delay > 0) {
      Thread.sleep(delay);
    }
  }
}

/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.jobs.backfill;

/**
 * SPI contract for a single-version backfill job.
 *
 * <p>Implementations are registered in {@link marquez.jobs.BackfillOrchestrator} and selected by
 * the {@code enabledVersions} list in {@link marquez.jobs.BackfillConfig}.
 *
 * <p>Each implementation must be idempotent: re-running the same version after a partial failure
 * must continue from the last successfully committed checkpoint rather than re-processing already
 * completed rows.
 */
public interface BackfillJob {

  /** Unique, stable identifier matching an entry in {@code BackfillConfig.enabledVersions}. */
  String version();

  /**
   * Executes the backfill.
   *
   * <p>Implementations should process data in small batches, committing a checkpoint after each
   * batch so the job can be resumed after a restart without re-processing completed work.
   *
   * @throws Exception on unrecoverable failure; the orchestrator will log the error and skip to the
   *     next version.
   */
  void run() throws Exception;
}

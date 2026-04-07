/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.jobs;

import io.dropwizard.lifecycle.Managed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.jobs.backfill.BackfillJob;

/**
 * Dropwizard {@link Managed} service that runs one or more {@link BackfillJob} implementations
 * sequentially in a dedicated background thread.
 *
 * <h2>Design goals</h2>
 *
 * <ul>
 *   <li><strong>Zero startup impact</strong> – all work runs in a daemon thread; server startup
 *       completes immediately.
 *   <li><strong>Sequential, ordered execution</strong> – jobs run in the order they appear in
 *       {@link BackfillConfig#getEnabledVersions()}, matching the dependency order of the
 *       migrations they mirror.
 *   <li><strong>Resumable</strong> – each {@link BackfillJob} implementation checkpoints its own
 *       progress so that a crash mid-way can be restarted without duplicating completed work.
 *   <li><strong>Graceful shutdown</strong> – {@link #stop()} asks the background thread to finish
 *       its current batch and then exit within 60 s.
 * </ul>
 *
 * <h2>Adding a new backfill version</h2>
 *
 * <ol>
 *   <li>Implement {@link BackfillJob} (e.g. {@code GraphV2BackfillJob}).
 *   <li>Register it here by calling {@code register(new GraphV2BackfillJob(...))}.
 *   <li>Add {@code "GRAPH_V2"} to {@code backfill.enabledVersions} in {@code config.yml} or the
 *       Helm values file.
 * </ol>
 */
@Slf4j
public class BackfillOrchestrator implements Managed {

  private final BackfillConfig config;

  /** Registry of all known backfill jobs, keyed by {@link BackfillJob#version()}. */
  private final Map<String, BackfillJob> registry = new LinkedHashMap<>();

  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "backfill-orchestrator");
            t.setDaemon(true);
            return t;
          });

  private volatile Future<?> runningFuture;

  public BackfillOrchestrator(@NonNull BackfillConfig config) {
    this.config = config;
  }

  /** Register a backfill job implementation. */
  public BackfillOrchestrator register(@NonNull BackfillJob job) {
    registry.put(job.version(), job);
    return this;
  }

  @Override
  public void start() {
    List<String> versions = config.getEnabledVersions();
    if (versions == null || versions.isEmpty()) {
      log.info("BackfillOrchestrator: no backfill versions configured — skipping.");
      return;
    }

    // Validate configured versions against registered implementations
    List<String> unknown = new ArrayList<>();
    for (String v : versions) {
      if (!registry.containsKey(v)) unknown.add(v);
    }
    if (!unknown.isEmpty()) {
      log.warn(
          "BackfillOrchestrator: unknown backfill version(s) {} — they will be skipped."
              + " Registered versions: {}",
          unknown,
          registry.keySet());
    }

    log.info(
        "BackfillOrchestrator: scheduling {} backfill version(s) to run in background: {}",
        versions.size(),
        versions);

    runningFuture =
        executor.submit(
            () -> {
              for (String version : versions) {
                BackfillJob job = registry.get(version);
                if (job == null) {
                  log.warn("BackfillOrchestrator: skipping unknown version '{}'.", version);
                  continue;
                }
                log.info("BackfillOrchestrator: starting backfill version '{}'.", version);
                try {
                  job.run();
                  log.info("BackfillOrchestrator: completed backfill version '{}'.", version);
                } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  log.warn(
                      "BackfillOrchestrator: interrupted while running version '{}' — stopping.",
                      version);
                  break;
                } catch (Exception e) {
                  log.error(
                      "BackfillOrchestrator: backfill version '{}' failed — continuing with next"
                          + " version.",
                      version,
                      e);
                }
              }
              log.info("BackfillOrchestrator: all configured backfill versions finished.");
            });
  }

  @Override
  public void stop() {
    log.info("BackfillOrchestrator: stopping...");
    if (runningFuture != null) {
      runningFuture.cancel(true);
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
        log.warn("BackfillOrchestrator: timed out waiting for background thread to stop.");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
    log.info("BackfillOrchestrator: stopped.");
  }
}

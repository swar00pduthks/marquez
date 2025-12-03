/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.jobs;

import com.google.common.util.concurrent.AbstractScheduledService;
import io.dropwizard.lifecycle.Managed;
import java.time.Duration;
import java.time.LocalTime;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

/** A job that refreshes run lineage materialized views on a fixed schedule in Marquez. */
@Slf4j
public class RunLineageMaterializeViewRefresherJob extends AbstractScheduledService
    implements Managed {

  private final int frequencyMinutes;
  private final Scheduler fixedRateScheduler;
  private final Jdbi jdbi;

  public RunLineageMaterializeViewRefresherJob(@NonNull final Jdbi jdbi) {
    this(jdbi, 60); // Default to 60 minutes
  }

  public RunLineageMaterializeViewRefresherJob(
      @NonNull final Jdbi jdbi, final int frequencyMinutes) {
    this.jdbi = jdbi;
    this.frequencyMinutes = frequencyMinutes;

    // Define fixed schedule to run every configured minutes
    this.fixedRateScheduler =
        Scheduler.newFixedRateSchedule(
            Duration.ZERO, // Start immediately
            Duration.ofMinutes(frequencyMinutes) // Then run every configured minutes
            );
    log.info(
        "Initialized RunLineageMaterializeViewRefresherJob with frequency: {} minutes",
        frequencyMinutes);
  }

  @Override
  protected Scheduler scheduler() {
    return fixedRateScheduler;
  }

  @Override
  public void start() throws Exception {
    log.info("Starting RunLineageMaterializeViewRefresherJob...");
    startAsync().awaitRunning();
    log.info(
        "RunLineageMaterializeViewRefresherJob started successfully. Will refresh views every '{}' mins.",
        frequencyMinutes);
  }

  @Override
  protected void runOneIteration() {
    try {
      log.info("RunLineageMaterializeViewRefresherJob: Starting iteration at {}", LocalTime.now());
      jdbi.useHandle(
          handle -> {
            log.info("RunLineageMaterializeViewRefresherJob: Refreshing run_lineage_view...");
            handle.execute("REFRESH MATERIALIZED VIEW run_lineage_view");
            log.info(
                "RunLineageMaterializeViewRefresherJob: Materialized view `run_lineage_view` refreshed.");

            log.info(
                "RunLineageMaterializeViewRefresherJob: Refreshing parent_run_lineage_view...");
            handle.execute("REFRESH MATERIALIZED VIEW run_parent_lineage_view");
            log.info(
                "RunLineageMaterializeViewRefresherJob: Materialized view `parent_run_lineage_view` refreshed.");
          });
      log.info("RunLineageMaterializeViewRefresherJob: Completed iteration successfully");

    } catch (Exception error) {
      log.error(
          "RunLineageMaterializeViewRefresherJob: Failed to refresh run lineage materialized views. Error: {}",
          error.getMessage(),
          error);
    }
  }

  @Override
  public void stop() throws Exception {
    log.info("Stopping run lineage materialized views job...");
    stopAsync().awaitTerminated();
  }
}

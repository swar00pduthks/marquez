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

  private final int FREQUENCY = 10;
  private final Scheduler fixedRateScheduler;
  private final Jdbi jdbi;

  public RunLineageMaterializeViewRefresherJob(@NonNull final Jdbi jdbi) {
    this.jdbi = jdbi;

    // Define fixed schedule and delay until the next 10-minute mark
    int MINUTES_IN_HOUR = 60;
    LocalTime now = LocalTime.now();
    int minutesRemaining =
        MINUTES_IN_HOUR - (now.getMinute() % 10); // Get remaining minutes until next 10-minute mark
    Duration duration = Duration.ofMinutes(minutesRemaining);
    this.fixedRateScheduler =
        Scheduler.newFixedRateSchedule(duration, Duration.ofMinutes(FREQUENCY));
  }

  @Override
  protected Scheduler scheduler() {
    return fixedRateScheduler;
  }

  @Override
  public void start() throws Exception {
    startAsync().awaitRunning();
    log.info("Refreshing run lineage materialized views every '{}' mins.", FREQUENCY);
  }

  @Override
  protected void runOneIteration() {
    try {
      log.info("Refreshing run lineage materialized views...");
      jdbi.useHandle(
          handle -> {
            handle.execute("REFRESH MATERIALIZED VIEW dataset_version_runs_view");
            log.info("Materialized view `dataset_version_runs_view` refreshed.");

            handle.execute("REFRESH MATERIALIZED VIEW parent_run_lineage_view");
            log.info("Materialized view `parent_run_lineage_view` refreshed.");
          });

    } catch (Exception error) {
      log.error("Failed to refresh run lineage materialized views. Retrying on next run...", error);
    }
  }

  @Override
  public void stop() throws Exception {
    log.info("Stopping run lineage materialized views job...");
    stopAsync().awaitTerminated();
  }
}

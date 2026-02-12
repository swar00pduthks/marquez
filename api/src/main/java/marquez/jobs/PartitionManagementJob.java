/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.jobs;

import com.google.common.util.concurrent.AbstractScheduledService;
import io.dropwizard.lifecycle.Managed;
import java.time.Duration;
import java.time.LocalDate;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.service.PartitionManagementService;
import org.jdbi.v3.core.Jdbi;

/**
 * A job that ensures database partitions are created in advance for denormalized lineage tables.
 * Runs at startup and periodically to maintain partitions for the next 12 months.
 */
@Slf4j
public class PartitionManagementJob extends AbstractScheduledService implements Managed {

  private final PartitionManagementService partitionManagementService;
  private final int monthsAhead;
  private final int frequencyDays;
  private final Scheduler fixedDelayScheduler;

  /**
   * Creates a new partition management job with default settings. Creates partitions 1 month ahead,
   * runs every 25 days.
   */
  public PartitionManagementJob(@NonNull final Jdbi jdbi) {
    this(jdbi, 1, 25);
  }

  /**
   * Creates a new partition management job with custom settings.
   *
   * @param jdbi JDBI instance for database operations
   * @param monthsAhead Number of months ahead to create partitions for
   * @param frequencyDays How often (in days) to run the partition check
   */
  public PartitionManagementJob(@NonNull final Jdbi jdbi, int monthsAhead, int frequencyDays) {
    this.partitionManagementService = new PartitionManagementService(jdbi, monthsAhead, 12);
    this.monthsAhead = monthsAhead;
    this.frequencyDays = frequencyDays;

    // Schedule to run with fixed delay between iterations
    this.fixedDelayScheduler =
        Scheduler.newFixedDelaySchedule(
            Duration.ZERO, // Run immediately on startup
            Duration.ofDays(frequencyDays));
  }

  @Override
  protected Scheduler scheduler() {
    return fixedDelayScheduler;
  }

  @Override
  public void start() throws Exception {
    startAsync().awaitRunning();
    log.info(
        "Partition management job started. Will create partitions {} months ahead, running every {} days.",
        monthsAhead,
        frequencyDays);
  }

  @Override
  protected void runOneIteration() {
    try {
      log.info("Running partition management to ensure future partitions exist...");

      LocalDate today = LocalDate.now();
      // Create partitions from current month for the next N months
      partitionManagementService.createPartitionsForPeriod(today, monthsAhead);

      log.info(
          "Partition management completed successfully. Ensured partitions exist for the next {} months.",
          monthsAhead);

    } catch (Exception error) {
      log.error("Failed to create partitions. Will retry on next scheduled run.", error);
    }
  }

  @Override
  public void stop() throws Exception {
    log.info("Stopping partition management job...");
    stopAsync().awaitTerminated();
  }
}

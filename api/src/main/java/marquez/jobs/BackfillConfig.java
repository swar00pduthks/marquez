/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.jobs;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Configuration for the async backfill orchestrator.
 *
 * <p>YAML example:
 *
 * <pre>
 * backfill:
 *   enabledVersions:
 *     - GRAPH_V1
 *   batchSize: 500
 *   delayBetweenBatchesMs: 50
 * </pre>
 *
 * <p>All fields can also be driven by environment variables via Dropwizard's {@code
 * EnvironmentVariableSubstitutor}:
 *
 * <pre>
 * MARQUEZ_BACKFILL_ENABLED_VERSIONS=GRAPH_V1
 * MARQUEZ_BACKFILL_BATCH_SIZE=500
 * </pre>
 *
 * <p>Multiple sequential backfill versions are listed in order:
 *
 * <pre>
 * enabledVersions:
 *   - GRAPH_V1
 *   - GRAPH_V2
 * </pre>
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackfillConfig {

  /** How many {@code lineage_events} rows to process per transaction. Default 500. */
  @Getter
  @Setter
  @JsonProperty("batchSize")
  @Builder.Default
  private int batchSize = 500;

  /**
   * Milliseconds to sleep between batches. A small pause reduces contention on the primary DB
   * connection pool while billions of rows are being backfilled. Default 50 ms.
   */
  @Getter
  @Setter
  @JsonProperty("delayBetweenBatchesMs")
  @Builder.Default
  private long delayBetweenBatchesMs = 50;

  /**
   * Ordered list of backfill job version identifiers to run sequentially. Each identifier maps to a
   * concrete {@link marquez.jobs.backfill.BackfillJob} implementation registered in {@link
   * marquez.jobs.BackfillOrchestrator}.
   *
   * <p>Known identifiers:
   *
   * <ul>
   *   <li>{@code GRAPH_V1} – Backfills the Apache AGE property graph from {@code lineage_events}.
   * </ul>
   */
  @Getter
  @Setter
  @JsonProperty("enabledVersions")
  @Builder.Default
  private List<String> enabledVersions = Collections.emptyList();
}

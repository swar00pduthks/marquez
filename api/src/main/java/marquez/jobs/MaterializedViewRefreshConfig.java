/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.jobs;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Configuration for materialized view refresh jobs. */
@NoArgsConstructor
public class MaterializedViewRefreshConfig {
  private static final int DEFAULT_FREQUENCY_MINUTES = 60;

  @Getter
  @Setter
  @JsonProperty("frequencyMinutes")
  private int frequencyMinutes = DEFAULT_FREQUENCY_MINUTES;

  /** Returns the frequency in minutes for refreshing materialized views. */
  public int getFrequencyMinutes() {
    return frequencyMinutes;
  }

  /** Returns {@code true} if a materialized view refresh policy has been configured. */
  public boolean hasMaterializedViewRefreshPolicy() {
    return (frequencyMinutes > 0);
  }
}

/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.client.models;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableMap;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import marquez.client.Utils;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class Run extends RunMeta {
  @Getter private final Instant createdAt;
  @Getter private final Instant updatedAt;
  @Getter private final RunState state;
  @Nullable private final Instant startedAt;
  @Nullable private final Long durationMs;
  @Nullable private final Instant endedAt;
  @Nullable private final JobVersionId jobVersion;
  @Getter private final Map<String, Object> facets;
  @Getter private final List<InputDatasetVersion> inputDatasetVersions;
  @Getter private final List<OutputDatasetVersion> outputDatasetVersions;

  public Run(
      @NonNull final String id,
      @NonNull final Instant createdAt,
      @NonNull final Instant updatedAt,
      @Nullable final Instant nominalStartTime,
      @Nullable final Instant nominalEndTime,
      @NonNull final RunState state,
      @Nullable final Instant startedAt,
      @Nullable final Instant endedAt,
      @Nullable final Long durationMs,
      @Nullable final Map<String, String> args,
      @Nullable final JobVersionId jobVersion,
      @Nullable final Map<String, Object> facets,
      @Nullable final List<InputDatasetVersion> inputDatasetVersions,
      @Nullable final List<OutputDatasetVersion> outputDatasetVersions) {
    super(id, nominalStartTime, nominalEndTime, args);
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.state = state;
    this.startedAt = startedAt;
    this.durationMs = durationMs;
    this.endedAt = endedAt;
    this.jobVersion = jobVersion;
    this.facets = (facets == null) ? ImmutableMap.of() : ImmutableMap.copyOf(facets);
    this.inputDatasetVersions =
        (inputDatasetVersions == null) ? Collections.emptyList() : inputDatasetVersions;
    this.outputDatasetVersions =
        (outputDatasetVersions == null) ? Collections.emptyList() : outputDatasetVersions;
  }

  public Optional<Instant> getStartedAt() {
    return Optional.ofNullable(startedAt);
  }

  public Optional<Instant> getEndedAt() {
    return Optional.ofNullable(endedAt);
  }

  public Optional<Long> getDurationMs() {
    return Optional.ofNullable(durationMs);
  }

  public Optional<JobVersionId> getJobVersion() {
    return Optional.ofNullable(jobVersion);
  }

  public boolean hasFacets() {
    return !facets.isEmpty();
  }

  public static Run fromJson(@NonNull final String json) {
    return Utils.fromJson(json, new TypeReference<Run>() {});
  }
}

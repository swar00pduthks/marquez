/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.ImmutableSet;
import jakarta.annotation.Nullable;
import java.net.URL;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import marquez.common.models.DatasetId;
import marquez.common.models.JobId;
import marquez.common.models.JobName;
import marquez.common.models.JobType;
import marquez.common.models.NamespaceName;

@Getter
@AllArgsConstructor
@ToString(of = {"namespace", "name", "type"})
public class JobData implements NodeData {
  UUID uuid;
  @NonNull JobId id;
  @NonNull JobType type;
  @NonNull JobName name;
  @NonNull String simpleName;
  @Nullable String parentJobName;
  @Nullable UUID parentJobUuid;
  @Getter @Nullable UUID currentRunUuid;
  @NonNull Instant createdAt;
  @NonNull Instant updatedAt;
  @NonNull NamespaceName namespace;
  @Setter ImmutableSet<DatasetId> inputs = ImmutableSet.of();
  @Setter ImmutableSet<UUID> inputUuids = ImmutableSet.of();
  @Setter ImmutableSet<DatasetId> outputs = ImmutableSet.of();
  @Setter ImmutableSet<UUID> outputUuids = ImmutableSet.of();
  @Nullable URL location;
  @Nullable String description;
  @Nullable @Setter Run latestRun;

  public Optional<URL> getLocation() {
    return Optional.ofNullable(location);
  }

  public Optional<String> getDescription() {
    return Optional.ofNullable(description);
  }

  public Optional<Run> getLatestRun() {
    return Optional.ofNullable(latestRun);
  }

  @JsonIgnore
  public UUID getUuid() {
    return uuid;
  }

  @JsonIgnore
  public Set<UUID> getInputUuids() {
    return inputUuids;
  }

  @JsonIgnore
  public Set<UUID> getOutputUuids() {
    return outputUuids;
  }

  public UUID getParentJobUuid() {
    return parentJobUuid;
  }
}

package marquez.service.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import marquez.common.models.DatasetId;
import marquez.common.models.JobName;
import marquez.common.models.NamespaceName;
import marquez.common.models.RunId;
import marquez.common.models.RunState;

@Getter
@AllArgsConstructor
@ToString(of = {"namespace", "jobName", "state"})
public class RunData implements NodeData {
  UUID uuid;
  @NonNull RunId id;
  @NonNull JobName jobName;
  @NonNull NamespaceName namespace;
  @NonNull RunState state;
  @NonNull Instant createdAt;
  @NonNull Instant updatedAt;
  @Nullable Instant startedAt;
  @Nullable Instant endedAt;
  @Nullable Long durationMs;
  @Nullable UUID jobUuid;
  @Nullable UUID jobVersionUuid;
  @Setter ImmutableSet<DatasetId> inputs = ImmutableSet.of();
  @Setter ImmutableSet<UUID> inputUuids = ImmutableSet.of();
  @Setter ImmutableSet<DatasetId> outputs = ImmutableSet.of();
  @Setter ImmutableSet<UUID> outputUuids = ImmutableSet.of();

  public Optional<Instant> getStartedAt() {
    return Optional.ofNullable(startedAt);
  }

  public Optional<Instant> getEndedAt() {
    return Optional.ofNullable(endedAt);
  }

  public Optional<Long> getDurationMs() {
    return Optional.ofNullable(durationMs);
  }

  public Optional<UUID> getJobUuid() {
    return Optional.ofNullable(jobUuid);
  }

  public Optional<UUID> getJobVersionUuid() {
    return Optional.ofNullable(jobVersionUuid);
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
} 
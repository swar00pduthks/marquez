/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db.models;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.Value;

@Value
public class DatasetFieldRow {
  @NonNull UUID uuid;
  @Nullable String type;
  @NonNull Instant createdAt;
  @NonNull Instant updatedAt;
  @NonNull UUID datasetUuid;
  @NonNull String name;
  @NonNull List<UUID> tagUuids;
  @Nullable String description;

  public Optional<String> getDescription() {
    return Optional.ofNullable(description);
  }
}

/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db.models;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.Value;

@Value
public class NamespaceRow {
  @NonNull UUID uuid;
  @NonNull Instant createdAt;
  @NonNull Instant updatedAt;
  @NonNull String name;
  @Nullable String description;
  @NonNull String currentOwnerName;
  @NonNull Boolean isHidden;

  public Optional<String> getDescription() {
    return Optional.ofNullable(description);
  }
}

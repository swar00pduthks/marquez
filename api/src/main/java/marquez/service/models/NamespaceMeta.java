/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service.models;

import jakarta.annotation.Nullable;
import java.util.Optional;
import lombok.NonNull;
import lombok.Value;
import marquez.common.models.OwnerName;

@Value
public class NamespaceMeta {
  @NonNull OwnerName ownerName;
  @Nullable String description;

  public Optional<String> getDescription() {
    return Optional.ofNullable(description);
  }
}

/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.client.models;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Nullable;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import marquez.client.Utils;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class Namespace extends NamespaceMeta {
  @Getter private final String name;
  @Getter private final Instant createdAt;
  @Getter private final Instant updatedAt;

  @Getter private final Boolean isHidden;

  public Namespace(
      @NonNull final String name,
      @NonNull final Instant createdAt,
      @NonNull final Instant updatedAt,
      final String ownerName,
      @Nullable final String description,
      @NonNull final Boolean isHidden) {
    super(ownerName, description);
    this.name = name;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.isHidden = isHidden;
  }

  public static Namespace fromJson(@NonNull final String json) {
    return Utils.fromJson(json, new TypeReference<Namespace>() {});
  }
}

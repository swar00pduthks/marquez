/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service.models;

import static marquez.common.models.DatasetType.DB_TABLE;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import marquez.common.models.DatasetId;
import marquez.common.models.DatasetName;
import marquez.common.models.Field;
import marquez.common.models.SourceName;
import marquez.common.models.TagName;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class DbTable extends Dataset {
  public DbTable(
      final DatasetId id,
      final DatasetName name,
      final DatasetName physicalName,
      final Instant createdAt,
      final Instant updatedAt,
      final SourceName sourceName,
      @Nullable final ImmutableList<Field> fields,
      @Nullable final ImmutableSet<TagName> tags,
      @Nullable final Instant lastModifiedAt,
      @Nullable final String lastLifecycleState,
      @Nullable final String description,
      @Nullable final UUID currentVersion,
      @Nullable final ImmutableMap<String, Object> facets,
      final boolean isDeleted) {
    super(
        id,
        DB_TABLE,
        name,
        physicalName,
        createdAt,
        updatedAt,
        sourceName,
        fields,
        tags,
        lastModifiedAt,
        lastLifecycleState,
        description,
        currentVersion,
        null,
        facets,
        isDeleted);
  }
}

/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.client.models;

import static marquez.client.models.DatasetType.DB_TABLE;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import marquez.client.Utils;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class DbTableMeta extends DatasetMeta {
  @Builder
  private DbTableMeta(
      final String physicalName,
      final String sourceName,
      @Nullable final List<Field> fields,
      @Nullable final Set<String> tags,
      @Nullable final String description,
      @Nullable final String runId) {
    super(DB_TABLE, physicalName, sourceName, fields, tags, description, runId);
  }

  @Override
  public String toJson() {
    return Utils.toJson(this);
  }
}

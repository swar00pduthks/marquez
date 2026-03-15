/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db.migrations;

import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Backfills the denormalized entity tables (datasets, dataset versions, and jobs) created in V88.
 * Uses only the V88-era schema columns. Later migrations (V92, V93) add additional columns and
 * backfill them separately.
 */
@Slf4j
public class V90__BackfillEntityDenormalizedTables implements JavaMigration {

  private Jdbi jdbi;

  @Override
  public MigrationVersion getVersion() {
    return MigrationVersion.fromVersion("90");
  }

  @Override
  public void migrate(Context context) throws Exception {
    log.info("Starting V90: Backfilling denormalized entity tables created in V88");

    if (context != null) {
      jdbi = Jdbi.create(context.getConnection());
    }

    try {
      List<UUID> namespaceUuids =
          jdbi.withHandle(
              h -> h.createQuery("SELECT uuid FROM namespaces").mapTo(UUID.class).list());
      for (UUID namespaceUuid : namespaceUuids) {
        try {
          jdbi.useTransaction(
              handle -> {
                upsertDatasetsDenormalized(handle, namespaceUuid);
                upsertDatasetVersionsDenormalized(handle, namespaceUuid);
                upsertJobsDenormalized(handle, namespaceUuid);
              });
        } catch (Exception e) {
          log.error("Failed to backfill denormalized entities for namespace: {}", namespaceUuid, e);
        }
      }
      log.info("V90 Migration completed successfully: All entities backfilled.");
    } catch (Exception e) {
      log.error("Failed to backfill denormalized entities", e);
      throw e;
    }
  }

  /**
   * Upserts dataset_denormalized using only V88-era columns. namespace_name, source_name,
   * last_modified_at, is_deleted are added in V92.
   */
  private void upsertDatasetsDenormalized(Handle handle, UUID namespaceUuid) {
    String sql =
        """
        INSERT INTO dataset_denormalized (
            uuid, type, created_at, updated_at, namespace_uuid, source_uuid, name,
            physical_name, description, current_version_uuid, tags, schema_location, lifecycle_state
        )
        SELECT
            d.uuid, d.type, d.created_at, d.updated_at, d.namespace_uuid, d.source_uuid,
            d.name, d.physical_name, d.description, d.current_version_uuid,
            (SELECT ARRAY_AGG(t.name) FROM tags t
             INNER JOIN datasets_tag_mapping m ON m.tag_uuid = t.uuid
             WHERE m.dataset_uuid = d.uuid),
            sv.schema_location, dv.lifecycle_state
        FROM datasets d
        LEFT JOIN dataset_versions dv ON d.current_version_uuid = dv.uuid
        LEFT JOIN stream_versions sv ON sv.dataset_version_uuid = dv.uuid
        WHERE d.namespace_uuid = :namespaceUuid
        ON CONFLICT (uuid, namespace_uuid) DO UPDATE SET
            updated_at = EXCLUDED.updated_at,
            current_version_uuid = EXCLUDED.current_version_uuid,
            tags = EXCLUDED.tags,
            schema_location = EXCLUDED.schema_location,
            lifecycle_state = EXCLUDED.lifecycle_state,
            description = EXCLUDED.description
        """;
    handle.createUpdate(sql).bind("namespaceUuid", namespaceUuid).execute();
  }

  /**
   * Upserts dataset_version_denormalized. The V88 column set is sufficient; no new columns are
   * added by V92/V93.
   */
  private void upsertDatasetVersionsDenormalized(Handle handle, UUID namespaceUuid) {
    String sql =
        """
        INSERT INTO dataset_version_denormalized (
            uuid, dataset_uuid, namespace_uuid, version, created_at, fields,
            schema_location, lifecycle_state
        )
        SELECT
            dv.uuid, dv.dataset_uuid, d.namespace_uuid, dv.version, dv.created_at, dv.fields,
            sv.schema_location, dv.lifecycle_state
        FROM dataset_versions dv
        INNER JOIN datasets d ON d.uuid = dv.dataset_uuid
        LEFT JOIN stream_versions sv ON sv.dataset_version_uuid = dv.uuid
        WHERE d.namespace_uuid = :namespaceUuid
        ON CONFLICT (uuid, namespace_uuid) DO NOTHING
        """;
    handle.createUpdate(sql).bind("namespaceUuid", namespaceUuid).execute();
  }

  /**
   * Upserts job_denormalized using only V88-era columns. namespace_name, simple_name,
   * parent_job_uuid, parent_job_name, current_location, current_inputs are added in V92.
   * input_uuids and output_uuids are added in V93.
   */
  private void upsertJobsDenormalized(Handle handle, UUID namespaceUuid) {
    String sql =
        """
        INSERT INTO job_denormalized (
            uuid, type, created_at, updated_at, namespace_uuid, name,
            description, current_version_uuid, tags
        )
        SELECT
            j.uuid, j.type, j.created_at, j.updated_at, j.namespace_uuid, j.name,
            j.description, j.current_version_uuid,
            (SELECT ARRAY_AGG(t.name) FROM tags t
             INNER JOIN jobs_tag_mapping m ON m.tag_uuid = t.uuid
             WHERE m.job_uuid = j.uuid)
        FROM jobs j
        WHERE j.namespace_uuid = :namespaceUuid
        ON CONFLICT (uuid, namespace_uuid) DO UPDATE SET
            updated_at = EXCLUDED.updated_at,
            current_version_uuid = EXCLUDED.current_version_uuid,
            tags = EXCLUDED.tags,
            description = EXCLUDED.description
        """;
    handle.createUpdate(sql).bind("namespaceUuid", namespaceUuid).execute();
  }

  @Override
  public String getDescription() {
    return "Backfill denormalized entity tables (datasets, jobs) created in V88";
  }

  @Override
  public Integer getChecksum() {
    return null;
  }

  @Override
  public boolean isUndo() {
    return false;
  }

  @Override
  public boolean canExecuteInTransaction() {
    return false;
  }

  @Override
  public boolean isBaselineMigration() {
    return false;
  }
}

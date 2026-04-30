-- V104: Add indexes to speed up hot-path queries.
--
-- run_facets is queried heavily by run_uuid + (lineage_event_type, name) for
-- per-run facet lookups; an additional composite index covering the JSON
-- expression (facet#>>'{context,context}') accelerates context-id filtering.
--
-- dataset_versions is queried by (uuid, namespace_name, dataset_name) to
-- resolve the `version` column; INCLUDE (version) makes it a covering index
-- so PostgreSQL can do an index-only scan and skip the heap fetch.
--
-- All four indexes use CREATE INDEX CONCURRENTLY so production writes are not
-- blocked during build (precedent: V47__add_lineage_event_indexes.sql).
-- Flyway's PostgreSQL dialect auto-detects CONCURRENTLY and runs each
-- statement outside a transaction.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_run_facets_run_uuid_event_name
    ON run_facets (run_uuid, lineage_event_type, name);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_run_facets_event_name_context_id
    ON run_facets (lineage_event_type, name, (facet #>> '{context,context}'));

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_dataset_versions_uuid_ns_name
    ON dataset_versions (uuid, namespace_name, dataset_name) INCLUDE (version);

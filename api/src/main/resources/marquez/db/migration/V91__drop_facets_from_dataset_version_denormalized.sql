-- SPDX-License-Identifier: Apache-2.0
-- V91: Drop facets from dataset_version_denormalized

ALTER TABLE dataset_version_denormalized DROP COLUMN facets;

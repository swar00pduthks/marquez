/* SPDX-License-Identifier: Apache-2.0 */

-- This migration initializes the Apache AGE extension if it doesn't already exist.
-- Note: The underlying PostgreSQL server must have 'age' in shared_preload_libraries
-- and enabled in azure.extensions for this to succeed on Azure Managed Instances.

DO $$
BEGIN
    -- Check if the 'age' extension is actually available in the PostgreSQL installation
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'age') THEN
        CREATE EXTENSION IF NOT EXISTS age;
    ELSE
        RAISE WARNING 'Apache AGE extension binary not found in pg_available_extensions. V3 Graph features will be disabled.';
    END IF;
END $$;

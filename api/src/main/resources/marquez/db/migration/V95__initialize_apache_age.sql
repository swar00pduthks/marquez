/* SPDX-License-Identifier: Apache-2.0 */

-- This migration initializes the Apache AGE extension if it doesn't already exist.
-- Note: The underlying PostgreSQL server must have 'age' in shared_preload_libraries
-- and enabled in azure.extensions for this to succeed on Azure Managed Instances.

-- Controlled by the ageEnabled Flyway placeholder. When ageEnabled=false this migration
-- is a complete no-op with zero SQL overhead and no WARNING log noise.
DO $$
BEGIN
    IF '${ageEnabled}' = 'true' THEN
        -- Check if the 'age' extension is actually available in the PostgreSQL installation
        IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'age') THEN
            BEGIN
                CREATE EXTENSION IF NOT EXISTS age;
            EXCEPTION
                WHEN insufficient_privilege THEN
                    RAISE WARNING 'Cannot CREATE EXTENSION age: insufficient privilege (role is not superuser). '
                        'Run: CREATE EXTENSION age; as a superuser to enable V3 Graph features. '
                        'Continuing without AGE.';
                WHEN OTHERS THEN
                    RAISE WARNING 'Cannot CREATE EXTENSION age: %. V3 Graph features will be disabled.', SQLERRM;
            END;
        ELSE
            RAISE WARNING 'Apache AGE extension binary not found in pg_available_extensions. V3 Graph features will be disabled.';
        END IF;
    END IF;
END $$;

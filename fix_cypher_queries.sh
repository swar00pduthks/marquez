#!/bin/bash

# Find all Java files in the v3 namespace
files=$(find api/src/main/java/marquez/v3 -name "*.java")

for file in $files; do
    # Replace cast(:params_json as agtype) with just :params_json
    sed -i 's/cast(:params_json as agtype)/:params_json/g' "$file"

    # Replace the JDBI .bind("params_json", paramsJson) with a custom PGobject binding
    sed -i 's/\.bind("params_json", paramsJson)/.bind("params_json", createAgtype(paramsJson))/g' "$file"

    # Check if createAgtype method exists in the file, if not, inject it and the PGobject import
    if ! grep -q "createAgtype" "$file"; then
        # Add import if missing
        if ! grep -q "org.postgresql.util.PGobject" "$file"; then
            sed -i '/import org.jdbi.v3.core.Jdbi;/a import org.postgresql.util.PGobject;' "$file"
            sed -i '/import org.jdbi.v3.core.Handle;/a import org.postgresql.util.PGobject;' "$file"
        fi

        # Add the helper method at the end of the class
        sed -i '/^}$/i \
    private static PGobject createAgtype(String json) { \
        try { \
            PGobject obj = new PGobject(); \
            obj.setType("agtype"); \
            obj.setValue(json); \
            return obj; \
        } catch (Exception e) { \
            throw new RuntimeException("Failed to create agtype", e); \
        } \
    }\n' "$file"
    fi
done

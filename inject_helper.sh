#!/bin/bash
for file in $(find api/src/main/java/marquez/v3 -name "*.java"); do
    sed -i '$ d' "$file"
    echo "
    private static org.postgresql.util.PGobject createAgtype(String json) {
        try {
            org.postgresql.util.PGobject obj = new org.postgresql.util.PGobject();
            obj.setType(\"agtype\");
            obj.setValue(json);
            return obj;
        } catch (Exception e) {
            throw new RuntimeException(\"Failed to create agtype\", e);
        }
    }
}
" >> "$file"
done

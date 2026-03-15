#!/bin/bash
files=$(find api/src/main/java/marquez/v3 api/src/test/java/marquez/v3 -name "*.java")
for file in $files; do
  if ! grep -q "Copyright" "$file"; then
    echo "Adding header to $file"
    echo -e "/*\n * Copyright 2018-2024 contributors to the Marquez project\n * SPDX-License-Identifier: Apache-2.0\n */\n\n$(cat "$file")" > "$file"
  fi
done

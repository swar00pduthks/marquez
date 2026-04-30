#!/bin/bash
ok=1
readarray -t files <<<"$(find api-v2/src/main -type f -name "*.java")"
for file in ${files[@]}; do
  if [[ ($file == *".java") ]]; then
    if ! grep -q Copyright "$file"; then
      ok=0
      echo "Copyright header not found in $file"
    fi
  fi
done
if [[ $ok == 0 ]]; then
  echo "FAIL"
else
  echo "PASS"
fi

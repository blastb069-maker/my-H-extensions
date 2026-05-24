#!/usr/bin/env bash
# Drop extension modules that have Gradle config but no Kotlin sources (Inspector will crash).
set -euo pipefail

removed=0
while IFS= read -r -d '' module; do
  if ! find "$module" -name '*.kt' -print -quit | grep -q .; then
    echo "Removing incomplete extension: ${module#src/}"
    rm -rf "$module"
    removed=$((removed + 1))
  fi
done < <(find src -mindepth 2 -maxdepth 2 -type d -print0)

echo "Pruned ${removed} incomplete extension module(s)."

#!/usr/bin/env bash
set -euo pipefail

echo "Checking required governance files..."
for f in CONTRIBUTING.md CODE_OF_CONDUCT.md CODE_QUALITY_AND_SECURITY.md RELEASING.md CHANGELOG.md why-the-dco.md .github/pull_request_template.md; do
  [[ -f "$f" ]] || { echo "Missing required file: $f"; exit 1; }
done

echo "Checking version vs CHANGELOG..."
if grep -q '^version=' gradle.properties; then
  VERSION=$(grep '^version=' gradle.properties | cut -d'=' -f2)
  if ! grep -q "$VERSION" CHANGELOG.md && ! grep -q '\[Unreleased\]' CHANGELOG.md; then
    echo "Current version $VERSION not found in CHANGELOG.md and no [Unreleased] section exists"
    exit 1
  fi
fi

echo "Repo standards OK."

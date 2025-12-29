#!/bin/bash
#
# Copyright 2018-2023 contributors to the Marquez project
# SPDX-License-Identifier: Apache-2.0
#
# Usage: $ ./publish.sh

set -e

export ORG_GRADLE_PROJECT_ossrhUsername="${ORG_GRADLE_PROJECT_ossrhUsername:-$OSSRH_USERNAME}"
export ORG_GRADLE_PROJECT_ossrhPassword="${ORG_GRADLE_PROJECT_ossrhPassword:-$OSSRH_PASSWORD}"

if [ -z "$ORG_GRADLE_PROJECT_ossrhUsername" ] || [ -z "$ORG_GRADLE_PROJECT_ossrhPassword" ]; then
  echo "ERROR: Sonatype credentials not set."
  echo "Set ORG_GRADLE_PROJECT_ossrhUsername and ORG_GRADLE_PROJECT_ossrhPassword (Central user token)."
  exit 1
fi

# Get, then decode, the GPG private key used to sign *-SNAPSHOT.jar
# Remove any whitespace/newlines from the base64 string before decoding
CLEAN_BASE64=$(echo "$GPG_SIGNING_KEY" | tr -d '\n\r[:space:]')
export ORG_GRADLE_PROJECT_signingKey=$(echo "$CLEAN_BASE64" | base64 -d 2>&1)

# Check if base64 decoding failed
if [ $? -ne 0 ] || [ -z "$ORG_GRADLE_PROJECT_signingKey" ]; then
  echo "ERROR: Failed to decode GPG signing key from base64"
  echo "Check that GPG_SIGNING_KEY is a valid base64-encoded string"
  exit 1
fi

# Verify passphrase is set (from CircleCI context)
if [ -z "$ORG_GRADLE_PROJECT_signingPassword" ]; then
  echo "ERROR: GPG signing password not set (ORG_GRADLE_PROJECT_signingPassword)"
  exit 1
fi

# Verify key format
if ! echo "$ORG_GRADLE_PROJECT_signingKey" | grep -q "BEGIN PGP PRIVATE KEY BLOCK"; then
  echo "ERROR: Decoded key does not appear to be a valid PGP private key"
  echo "Key should start with '-----BEGIN PGP PRIVATE KEY BLOCK-----'"
  exit 1
fi

echo "GPG signing key decoded and verified successfully"

# Publish *.jar
./gradlew publish --no-daemon --info

echo "DONE!"

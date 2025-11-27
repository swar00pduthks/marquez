#!/bin/bash
#
# Copyright 2018-2023 contributors to the Marquez project
# SPDX-License-Identifier: Apache-2.0
#
# Usage: $ ./publish.sh

set -e

# Get, then decode, the GPG private key used to sign *-SNAPSHOT.jar
export ORG_GRADLE_PROJECT_signingKey=$(echo $GPG_SIGNING_KEY | base64 -d)

# Verify the key was decoded correctly (for debugging)
if [ -z "$ORG_GRADLE_PROJECT_signingKey" ]; then
  echo "ERROR: Failed to decode GPG signing key"
  exit 1
fi

# Verify passphrase is set (from CircleCI context)
if [ -z "$ORG_GRADLE_PROJECT_signingPassword" ]; then
  echo "ERROR: GPG signing password not set"
  exit 1
fi

# Verify key format
if ! echo "$ORG_GRADLE_PROJECT_signingKey" | grep -q "BEGIN PGP PRIVATE KEY BLOCK"; then
  echo "ERROR: Decoded key does not appear to be a valid PGP private key"
  exit 1
fi

echo "GPG signing key decoded and verified"

# Publish *.jar
./gradlew publish

echo "DONE!"

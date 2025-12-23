#!/bin/bash
#
# Copyright 2018-2023 contributors to the Marquez project
# SPDX-License-Identifier: Apache-2.0
#
# Usage: $ ./publish.sh

set -e

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
./gradlew publish

# For Maven-API-like plugins (Gradle's maven-publish), we need to notify the Central Publisher Portal
# that the deployment is complete by making a POST request to the manual upload endpoint
# This ensures the deployment is visible in https://central.sonatype.com/publishing
if [ -n "$OSSRH_USERNAME" ] && [ -n "$OSSRH_PASSWORD" ]; then
  echo "Notifying Central Publisher Portal of completed deployment..."
  
  # Make POST request to finalize the deployment
  HTTP_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
    -u "$OSSRH_USERNAME:$OSSRH_PASSWORD" \
    "https://ossrh-staging-api.central.sonatype.com/service/local/staging/manual/upload/defaultRepository/")
  
  HTTP_CODE=$(echo "$HTTP_RESPONSE" | tail -n1)
  HTTP_BODY=$(echo "$HTTP_RESPONSE" | sed '$d')
  
  if [ "$HTTP_CODE" -ge 200 ] && [ "$HTTP_CODE" -lt 300 ]; then
    echo "Successfully notified Central Publisher Portal (HTTP $HTTP_CODE)"
    echo "$HTTP_BODY"
  else
    echo "WARNING: Failed to notify Central Publisher Portal (HTTP $HTTP_CODE)"
    echo "$HTTP_BODY"
    echo "Deployment may not be visible in the Central Publisher Portal"
  fi
else
  echo "WARNING: OSSRH credentials not set, skipping Central Publisher Portal notification"
fi

echo "DONE!"

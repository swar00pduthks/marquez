#!/bin/bash
#
# Copyright 2018-2023 contributors to the Marquez project
# SPDX-License-Identifier: Apache-2.0
#
# Usage: $ ./build-and-push.sh <version> [api|web|both]
# Examples:
#   ./build-and-push.sh 0.52.27 api    # Build and push only API
#   ./build-and-push.sh 0.52.27 web    # Build and push only Web
#   ./build-and-push.sh 0.52.27 both   # Build and push both (default)

set -eu

readonly SEMVER_REGEX="^[0-9]+(\.[0-9]+){2}(-rc\.[0-9]+)?$" # X.Y.Z
readonly ORG="swar00pduth"

# Change working directory to project root
project_root=$(git rev-parse --show-toplevel)
cd "${project_root}"

# Version X.Y.Z of Marquez images to build
version="${1}"

# Image type to build (api, web, or both)
image_type="${2:-both}"

# Ensure valid version
if [[ ! "${version}" =~ ${SEMVER_REGEX} ]]; then
  echo "Version must match ${SEMVER_REGEX}"
  exit 1
fi

echo "Building ${image_type} images (tag: ${version})..."

# Function to build and push API image
build_api() {
  echo "Building API image..."
  docker build --no-cache --tag "${ORG}/marquez:${version}" .
  docker tag "${ORG}/marquez:${version}" "${ORG}/marquez:latest"
  docker push "${ORG}/marquez:${version}"
  docker push "${ORG}/marquez:latest"
  echo "API image built and pushed successfully!"
}

# Function to build and push Web image
build_web() {
  echo "Building Web image..."
  cd "${project_root}"/web
  docker build --no-cache --tag "${ORG}/marquez-web:${version}" .
  docker tag "${ORG}/marquez-web:${version}" "${ORG}/marquez-web:latest"
  docker push "${ORG}/marquez-web:${version}"
  docker push "${ORG}/marquez-web:latest"
  echo "Web image built and pushed successfully!"
}

# Build based on image_type argument
case "${image_type}" in
  "api")
    build_api
    ;;
  "web")
    build_web
    ;;
  "both")
    build_api
    build_web
    ;;
  *)
    echo "Invalid image type: ${image_type}"
    echo "Valid options: api, web, both"
    exit 1
    ;;
esac

echo "DONE!"

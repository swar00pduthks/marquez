#!/bin/bash
#
# Copyright 2018-2023 contributors to the Marquez project
# SPDX-License-Identifier: Apache-2.0
#
# Usage: $ ./get-jdk17.sh

set -e

# If JDK 17 is already present on the machine image, skip download.
if java -version 2>&1 | grep -q 'version "17'; then
  echo "JDK 17 already available"
  java -version
  echo "DONE!"
  exit 0
fi

# Adoptium Temurin 17 (adoptium.jfrog.io was retired in 2024; use packages.adoptium.net)
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo apt-key add -
echo "deb https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update && sudo apt-get install --yes temurin-17-jdk
sudo update-alternatives --set java /usr/lib/jvm/temurin-17-jdk-amd64/bin/java
sudo update-alternatives --set javac /usr/lib/jvm/temurin-17-jdk-amd64/bin/javac
java -version

echo "DONE!"

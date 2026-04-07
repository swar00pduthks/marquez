#!/bin/bash
#
# Copyright 2018-2022 contributors to the Marquez project
# SPDX-License-Identifier: Apache-2.0
#
# Usage: $ ./get-k6.sh

set -e

# Add k6 GPG key to keyring
sudo gpg --no-default-keyring \
    --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
    --keyserver hkp://keyserver.ubuntu.com:80 \
    --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69

# Add k6 apt repository
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | \
    sudo tee /etc/apt/sources.list.d/k6.list

# Install k6, then verify
sudo apt-get update && sudo apt-get install -y k6 && k6 version

echo "DONE!"

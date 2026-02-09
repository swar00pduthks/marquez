#!/bin/sh
#
# Copyright 2018-2023 contributors to the Marquez project
# SPDX-License-Identifier: Apache-2.0
#
# Usage: $ ./entrypoint.sh

set -e

# Export environment variables for setupProxy.js to read
export MARQUEZ_HOST=${MARQUEZ_HOST:-"localhost"}
export MARQUEZ_PORT=${MARQUEZ_PORT:-"5000"}
export WEB_PORT=${WEB_PORT:-"3000"}

node setupProxy.js

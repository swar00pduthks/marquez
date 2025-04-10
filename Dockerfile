# Copyright 2018-2023 contributors to the Marquez project
# SPDX-License-Identifier: Apache-2.0

FROM eclipse-temurin:17 AS base
WORKDIR /usr/src/app
COPY gradle gradle
COPY gradle.properties gradle.properties
COPY gradlew gradlew
COPY settings.gradle settings.gradle

# Make wrapper executable and fix line endings
RUN chmod +x ./gradlew
RUN sed -i 's/\r$//' ./gradlew
RUN ./gradlew --version

FROM base AS build
WORKDIR /usr/src/app
COPY build.gradle build.gradle
COPY api ./api
COPY clients/java ./clients/java
RUN ./gradlew --no-daemon clean :api:shadowJar

FROM eclipse-temurin:17
# Update packages and install specific secure version of libpam0g
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        postgresql-client \
        bash \
        coreutils \
        libpam0g=1.5.2-5ubuntu1 \
        libpam-modules=1.5.2-5ubuntu1 \
        libpam-modules-bin=1.5.2-5ubuntu1 && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /usr/src/app
COPY --from=build /usr/src/app/api/build/libs/marquez-*.jar /usr/src/app
COPY marquez.dev.yml marquez.dev.yml

# Create and set up entrypoint script
RUN echo '#!/bin/bash' > /usr/src/app/entrypoint.sh && \
    echo 'set -e' >> /usr/src/app/entrypoint.sh && \
    echo 'if [[ -z "${MARQUEZ_CONFIG}" ]]; then' >> /usr/src/app/entrypoint.sh && \
    echo '  MARQUEZ_CONFIG="marquez.dev.yml"' >> /usr/src/app/entrypoint.sh && \
    echo '  echo "WARNING '\''MARQUEZ_CONFIG'\'' not set, using development configuration."' >> /usr/src/app/entrypoint.sh && \
    echo 'fi' >> /usr/src/app/entrypoint.sh && \
    echo 'JAVA_OPTS="${JAVA_OPTS} -Duser.timezone=UTC -Dlog4j2.formatMsgNoLookups=true"' >> /usr/src/app/entrypoint.sh && \
    echo 'java ${JAVA_OPTS} -jar marquez-*.jar server ${MARQUEZ_CONFIG}' >> /usr/src/app/entrypoint.sh && \
    chmod +x /usr/src/app/entrypoint.sh

EXPOSE 5000 5001
ENTRYPOINT ["/usr/src/app/entrypoint.sh"]

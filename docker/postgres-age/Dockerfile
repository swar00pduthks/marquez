# Stage 1: Build the Apache AGE extension from source
FROM postgres:14 AS builder

# Install build dependencies
RUN apt-get update && apt-get install -y \
    build-essential \
    git \
    postgresql-server-dev-14 \
    flex \
    bison \
    && rm -rf /var/lib/apt/lists/*

# Clone and build Apache AGE for PG 14
RUN git clone -b PG14 --depth 1 https://github.com/apache/age.git /tmp/age \
    && cd /tmp/age \
    && make \
    && make install

# Stage 2: Create the minimal production-ready database image
FROM postgres:14

# Copy the compiled Apache AGE extension binaries and libraries from the builder stage
COPY --from=builder /usr/lib/postgresql/14/lib/age.so /usr/lib/postgresql/14/lib/
COPY --from=builder /usr/share/postgresql/14/extension/age* /usr/share/postgresql/14/extension/

# Add extension initialization to the standard postgres entrypoint
RUN echo "CREATE EXTENSION IF NOT EXISTS age;" > /docker-entrypoint-initdb.d/00_init_age.sql \
    && echo "LOAD 'age';" >> /docker-entrypoint-initdb.d/00_init_age.sql \
    && echo "SET search_path = ag_catalog, \"\$user\", public;" >> /docker-entrypoint-initdb.d/00_init_age.sql

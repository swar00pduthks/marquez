/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v2;

import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import marquez.v2.resources.LineageResourceV2;
import org.jdbi.v3.core.Jdbi;
import io.dropwizard.jdbi3.JdbiFactory;

public class MarquezAppV2 extends Application<MarquezConfigV2> {
    private static final String APP_NAME = "MarquezV2";

    public static void main(String[] args) throws Exception {
        new MarquezAppV2().run(args);
    }

    @Override
    public String getName() {
        return APP_NAME;
    }

    @Override
    public void initialize(Bootstrap<MarquezConfigV2> bootstrap) {
        // Initialization logic
    }

    @Override
    public void run(MarquezConfigV2 config, Environment env) {
        final JdbiFactory factory = new JdbiFactory();

        // Ensure AGE extension is loaded on every connection checkout from the pool
        config.getDataSourceFactory().setInitializationQuery("LOAD 'age'; SET search_path = ag_catalog, \"$user\", public;");

        final Jdbi jdbi = factory.build(env, config.getDataSourceFactory(), "postgresql-age");

        // Create the extension itself once at startup if not exists (Requires SUPERUSER, safe in custom docker images)
        try {
            jdbi.useHandle(handle -> {
                handle.execute("CREATE EXTENSION IF NOT EXISTS age");
            });
        } catch (Exception e) {
            // Log warning in production if user lacks SUPERUSER
        }

        // Initialize DAO
        marquez.v2.db.GraphDao graphDao = new marquez.v2.db.GraphDao();
        graphDao.initGraph(jdbi, "marquez_graph");

        // Register v2 Resources
        env.jersey().register(new marquez.v2.resources.OpenLineageResourceV2(jdbi, graphDao));
        env.jersey().register(new LineageResourceV2(jdbi));
        env.jersey().register(new marquez.v2.resources.DatasetResourceV2(jdbi));

        // Register Phase 2 Endpoint Stubs
        env.jersey().register(new marquez.v2.resources.V2ResourceStubs.NamespaceResourceV2());
        env.jersey().register(new marquez.v2.resources.V2ResourceStubs.JobResourceV2());
        env.jersey().register(new marquez.v2.resources.V2ResourceStubs.RunResourceV2());
        env.jersey().register(new marquez.v2.resources.V2ResourceStubs.TagResourceV2());
        env.jersey().register(new marquez.v2.resources.V2ResourceStubs.SourceResourceV2());
        env.jersey().register(new marquez.v2.resources.V2ResourceStubs.ColumnLineageResourceV2());
    }
}

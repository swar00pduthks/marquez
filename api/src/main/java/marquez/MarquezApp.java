/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez;

import com.codahale.metrics.jdbi3.InstrumentedSqlLogger;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.db.ManagedDataSource;
import io.dropwizard.jdbi3.JdbiFactory;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.dropwizard.DropwizardExports;
import io.prometheus.client.hotspot.DefaultExports;
import io.prometheus.client.servlet.jakarta.exporter.MetricsServlet;
import io.sentry.Sentry;
import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.api.filter.JobRedirectFilter;
import marquez.api.filter.exclusions.Exclusions;
import marquez.api.filter.exclusions.ExclusionsConfig;
import marquez.cli.DbMigrateCommand;
import marquez.cli.DbRetentionCommand;
import marquez.cli.MetadataCommand;
import marquez.cli.SeedCommand;
import marquez.common.Utils;
import marquez.db.DbMigration;
import marquez.jobs.BackfillConfig;
import marquez.jobs.BackfillOrchestrator;
import marquez.jobs.DbRetentionJob;
import marquez.jobs.MaterializeViewRefresherJob;
import marquez.jobs.PartitionManagementJob;
import marquez.jobs.backfill.DatasetFacetsPartitionBackfillJob;
import marquez.jobs.backfill.DenormV1BackfillJob;
import marquez.jobs.backfill.GraphV1BackfillJob;
import marquez.jobs.backfill.RunFacetsPartitionBackfillJob;
import marquez.logging.DelegatingSqlLogger;
import marquez.logging.LabelledSqlLogger;
import marquez.logging.LoggingMdcFilter;
import marquez.service.DatabaseMetrics;
import marquez.tracing.SentryConfig;
import marquez.tracing.TracingContainerResponseFilter;
import marquez.tracing.TracingSQLLogger;
import marquez.tracing.TracingServletFilter;
import org.flywaydb.core.api.FlywayException;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.jackson2.Jackson2Config;
import org.jdbi.v3.jackson2.Jackson2Plugin;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

@Slf4j
public final class MarquezApp extends Application<MarquezConfig> {
  private static final String APP_NAME = "MarquezApp";
  private static final String DB_SOURCE_NAME = APP_NAME + "-source";
  private static final String DB_POSTGRES = "postgresql";
  private static final boolean ERROR_ON_UNDEFINED = false;

  // Monitoring
  private static final String PROMETHEUS = "prometheus";
  private static final String PROMETHEUS_V2 = "prometheus_v2";
  private static final String PROMETHEUS_ENDPOINT = "/metrics";
  private static final String PROMETHEUS_ENDPOINT_V2 = "/v2beta/metrics";

  private static Jdbi jdbiInstance; // Static reference for testing

  public static Jdbi getJdbiInstanceForTesting() { // Static getter for testing
    return jdbiInstance;
  }

  public static void main(final String[] args) throws Exception {
    new MarquezApp().run(args);
  }

  @Override
  public String getName() {
    return APP_NAME;
  }

  @Override
  public void initialize(@NonNull Bootstrap<MarquezConfig> bootstrap) {
    // Enable Prometheus metrics
    CollectorRegistry.defaultRegistry.register(
        new DropwizardExports(bootstrap.getMetricRegistry()));
    DatabaseMetrics.registry.register(new DropwizardExports(bootstrap.getMetricRegistry()));
    DefaultExports.initialize();
    DefaultExports.register(DatabaseMetrics.registry);

    bootstrap.setConfigurationSourceProvider(
        new SubstitutingSourceProvider(
            bootstrap.getConfigurationSourceProvider(),
            new EnvironmentVariableSubstitutor(ERROR_ON_UNDEFINED)));

    bootstrap.addCommand(new DbMigrateCommand());
    bootstrap.addCommand(new DbRetentionCommand());
    bootstrap.addCommand(new MetadataCommand());
    bootstrap.addCommand(new SeedCommand());

    bootstrap.getObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    Utils.addZonedDateTimeMixin(bootstrap.getObjectMapper());

    bootstrap.addBundle(
        new AssetsBundle(
            "/assets",
            "/graphql-playground",
            "graphql-playground/index.htm",
            "graphql-playground"));

    bootstrap.addBundle(
        new AssetsBundle("/assets/swagger-ui", "/api/swagger-ui", "index.html", "swagger-ui"));

    bootstrap.addBundle(new AssetsBundle("/assets", "/api/assets", null, "assets"));
  }

  @Override
  public void run(@NonNull MarquezConfig config, @NonNull Environment env) {
    final DataSourceFactory sourceFactory = config.getDataSourceFactory();
    final ManagedDataSource source = sourceFactory.build(env.metrics(), DB_SOURCE_NAME);

    log.info("Running startup actions...");

    try {
      DbMigration.migrateDbOrError(
          config.getFlywayFactory(), source, config.isMigrateOnStartup(), config.isAgeEnabled());
    } catch (FlywayException errorOnDbMigrate) {
      log.info("Stopping app...");
      onFatalError(errorOnDbMigrate);
    }

    if (isSentryEnabled(config)) {
      Sentry.init(
          options -> {
            options.setTracesSampleRate(config.getSentry().getTracesSampleRate());
            options.setEnvironment(config.getSentry().getEnvironment());
            options.setDsn(config.getSentry().getDsn());
            options.setDebug(config.getSentry().isDebug());
          });

      env.servlets()
          .addFilter("tracing-filter", new TracingServletFilter())
          .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
      env.jersey().register(new TracingContainerResponseFilter());
    }

    final Jdbi jdbi = newJdbi(config, env, source);
    jdbiInstance = jdbi; // Assign to static field

    final MarquezContext marquezContext =
        MarquezContext.builder()
            .jdbi(jdbi)
            .searchConfig(config.getSearchConfig())
            .tags(config.getTags())
            .build();

    registerResources(config, env, marquezContext);
    registerServlets(env);
    registerFilters(env, marquezContext);

    if (config.hasDbRetentionPolicy()) {
      env.lifecycle().manage(new DbRetentionJob(jdbi, config.getDbRetention()));
    }

    // Initialize materialized view refresh jobs with configuration
    int refreshFrequency =
        config.getMaterializedViewRefresh() != null
            ? config.getMaterializedViewRefresh().getFrequencyMinutes()
            : 60; // Default to 60 minutes
    env.lifecycle().manage(new MaterializeViewRefresherJob(jdbi, refreshFrequency));
    // DISABLED: run_lineage_view replaced with run_lineage_denormalized table
    // (event-driven updates
    // via triggers)
    // env.lifecycle().manage(new RunLineageMaterializeViewRefresherJob(jdbi,
    // refreshFrequency));

    // Initialize partition management job to ensure future partitions exist
    // Creates partitions for current month + 12 months ahead, runs every 7 days
    env.lifecycle().manage(new PartitionManagementJob(jdbi, 12, 7));

    ExclusionsConfig exclusions = config.getExclude();
    Exclusions.use(exclusions);
  }

  private boolean isSentryEnabled(MarquezConfig config) {
    return config.getSentry() != null
        && !config.getSentry().getDsn().equals(SentryConfig.DEFAULT_DSN);
  }

  private Jdbi newJdbi(
      @NonNull MarquezConfig config, @NonNull Environment env, @NonNull ManagedDataSource source) {

    final JdbiFactory factory = new JdbiFactory();
    final Jdbi jdbi =
        factory
            .build(env, config.getDataSourceFactory(), source, DB_POSTGRES)
            .installPlugin(new SqlObjectPlugin())
            .installPlugin(new PostgresPlugin())
            .installPlugin(new Jackson2Plugin());
    SqlLogger sqlLogger =
        new DelegatingSqlLogger(new LabelledSqlLogger(), new InstrumentedSqlLogger(env.metrics()));
    if (isSentryEnabled(config)) {
      sqlLogger = new TracingSQLLogger(sqlLogger);
    }
    jdbi.setSqlLogger(sqlLogger);
    jdbi.getConfig(Jackson2Config.class).setMapper(Utils.getMapper());
    return jdbi;
  }

  public void registerResources(
      @NonNull MarquezConfig config, @NonNull Environment env, MarquezContext context) {

    final Jdbi jdbi = context.getJdbi();

    // Build orchestrator early so both AGE-dependent and relational backfill jobs can register.
    BackfillConfig backfillConfig =
        config.getBackfill() != null ? config.getBackfill() : BackfillConfig.builder().build();

    // The run_facets online partitioning cutover (RUN_FACETS_PARTITION_V1) must always complete,
    // regardless of which other backfills an operator enabled — otherwise a large table armed by
    // V108 would never finish its copy + swap. Inject it into the enabled set if absent; on
    // small/empty installs V108 already swapped inline and the job no-ops immediately.
    java.util.List<String> enabledVersions =
        new java.util.ArrayList<>(
            backfillConfig.getEnabledVersions() != null
                ? backfillConfig.getEnabledVersions()
                : java.util.List.of());
    for (String v :
        java.util.List.of(
            RunFacetsPartitionBackfillJob.VERSION, DatasetFacetsPartitionBackfillJob.VERSION)) {
      if (!enabledVersions.contains(v)) {
        enabledVersions.add(v);
      }
    }
    backfillConfig.setEnabledVersions(enabledVersions);

    final BackfillOrchestrator backfillOrchestrator = new BackfillOrchestrator(backfillConfig);

    // Relational jobs need only the DB — register regardless of AGE availability.
    backfillOrchestrator.register(new DenormV1BackfillJob(jdbi, backfillConfig));
    backfillOrchestrator.register(new RunFacetsPartitionBackfillJob(jdbi, backfillConfig));
    backfillOrchestrator.register(new DatasetFacetsPartitionBackfillJob(jdbi, backfillConfig));

    // Register V3 Graph API Resources conditionally to prevent crashing standard V1 databases
    final AtomicBoolean ageEnabled = new AtomicBoolean(false);
    if (config.isAgeEnabled()) {
      log.info("Starting V3 Graph API registration check...");
      try {
        jdbi.useHandle(
            handle -> {
              java.sql.Connection conn = handle.getConnection();
              try (java.sql.Statement stmt = conn.createStatement()) {
                log.info("Attempting to verify/create AGE extension...");
                try {
                  stmt.execute("CREATE EXTENSION IF NOT EXISTS age");
                  log.info("Finished CREATE EXTENSION command.");
                } catch (Exception e) {
                  log.info(
                      "Note: CREATE EXTENSION IF NOT EXISTS age message (standard on Azure/non-superuser): {}",
                      e.getMessage());
                }

                log.info("Attempting to LOAD 'age'...");
                try {
                  stmt.execute("LOAD 'age'");
                  log.info("Successfully LOADed 'age'.");
                } catch (Exception e) {
                  log.info(
                      "Note: LOAD 'age' failed, but continuing as it may be preloaded: {}",
                      e.getMessage());
                }

                log.info("Attempting to set search_path for AGE...");
                try {
                  stmt.execute("SET search_path = ag_catalog, \"$user\", public");
                  log.info("Successfully set search_path for AGE.");
                } catch (Exception e) {
                  log.info("Note: SET search_path failed, but continuing: {}", e.getMessage());
                }

                // Final check: confirm AGE extension exists in database
                try (java.sql.ResultSet rs =
                    stmt.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'age'")) {
                  ageEnabled.set(rs.next());
                }
              }
            });
        if (ageEnabled.get()) {
          log.info("Marquez V3 initialization complete (ageEnabled=true).");
        } else {
          log.info("Marquez V3 initialization complete (ageEnabled=false).");
        }
      } catch (Exception e) {
        log.warn("Failed V3 check. V3 Graph API will be disabled. Reason: {}", e.getMessage(), e);
      }
    } else {
      log.info("AGE disabled by configuration (ageEnabled=false). Skipping V3 Graph API.");
    }

    if (ageEnabled.get()) {
      marquez.v3.db.GraphDao graphDao = new marquez.v3.db.GraphDao();
      graphDao.initGraph(jdbi, "marquez_graph");
      marquez.v3.db.GraphWriter graphWriter = new marquez.v3.db.GraphWriter(graphDao);

      // Wire graph writes into the V1 service path (fire-and-forget)
      context.getOpenLineageService().enableGraphWrites(graphWriter, jdbi);

      env.jersey()
          .register(
              new marquez.v3.resources.OpenLineageResourceV3(
                  jdbi, graphWriter, context.getOpenLineageService()));
      env.jersey().register(new marquez.v3.resources.DatasetResourceV3(jdbi));
      env.jersey().register(new marquez.v3.resources.NamespaceDatasetResourceV3(jdbi));
      env.jersey().register(new marquez.v3.resources.NamespaceResourceV3(jdbi));
      env.jersey().register(new marquez.v3.resources.JobResourceV3(jdbi));
      env.jersey().register(new marquez.v3.resources.NamespaceJobResourceV3(jdbi));
      env.jersey().register(new marquez.v3.resources.EventsResourceV3(jdbi));
      env.jersey().register(new marquez.v3.resources.RunResourceV3(jdbi));
      env.jersey().register(new marquez.v3.resources.TagResourceV3(jdbi));
      env.jersey().register(new marquez.v3.resources.SourceResourceV3(jdbi));
      env.jersey().register(new marquez.v3.resources.ColumnLineageResourceV3(jdbi));
      env.jersey().register(new marquez.v3.resources.StatsResourceV3(context.getStatsService()));
      // NOTE: OpenLineageResourceV3Beta intentionally NOT registered — its pure-Cypher query
      // uses `[:INPUT_TO|OUTPUT_FROM*1..N]` edge-type alternation in a variable-length pattern,
      // which is not supported by Apache AGE. Needs BFS rewrite (alternating single-type hops).
      // Tracked as a follow-up; UI lineage view via /api/v3beta/lineage will 404 until fixed.

      // GRAPH_V1 requires AGE — only register it here when AGE is confirmed available
      if (backfillOrchestrator != null) {
        backfillOrchestrator.register(new GraphV1BackfillJob(jdbi, graphWriter, backfillConfig));
      }
    }

    // Manage the orchestrator lifecycle after all jobs are registered
    if (backfillOrchestrator != null) {
      env.lifecycle().manage(backfillOrchestrator);
      log.info(
          "BackfillOrchestrator registered with versions: {}", backfillConfig.getEnabledVersions());
    }

    if (config.getGraphql().isEnabled()) {
      env.servlets()
          .addServlet("api/v1-beta/graphql", context.getGraphqlServlet())
          .addMapping("/api/v1-beta/graphql", "/api/v1/schema.json");
    }

    // Prometheus metrics endpoint
    env.servlets().addServlet(PROMETHEUS, new MetricsServlet()).addMapping(PROMETHEUS_ENDPOINT);

    log.debug("Registering resources...");
    for (final Object resource : context.getResources()) {
      env.jersey().register(resource);
    }
  }

  private void registerServlets(@NonNull Environment env) {
    log.debug("Registering servlets...");
    env.servlets()
        .addServlet(PROMETHEUS_V2, new MetricsServlet(DatabaseMetrics.registry))
        .addMapping(PROMETHEUS_ENDPOINT_V2);
  }

  private void registerFilters(@NonNull Environment env, MarquezContext marquezContext) {
    env.jersey().getResourceConfig().register(new LoggingMdcFilter());
    env.jersey()
        .getResourceConfig()
        .register(new JobRedirectFilter(marquezContext.getJobService()));
  }
}

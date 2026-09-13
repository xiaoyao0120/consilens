package com.consilens.cluster.application;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.compare.CompareRuntime;
import com.consilens.core.compare.DefaultCompareRuntime;
import com.consilens.core.diff.DiffResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Process entry point used by a YARN ApplicationMaster or Kubernetes Job. It is
 * a plain main class and knows nothing about the platform that launched it: the
 * YARN {@link ConsilensApplicationMaster} wrapper handles RM registration and
 * final-status reporting, exactly as Spark's ApplicationMaster wraps the user's
 * driver. Credentials are resolved from the application process environment
 * while loading the descriptor, so they never pass through the cluster
 * submission envelope.
 */
public class ClusterComparisonCoordinator {

    private final ComparisonDescriptorOpener descriptorOpener;
    private final ConfigurationManager configurationManager;
    private final CompareRequestFactory compareRequestFactory;
    private final CompareRuntime compareRuntime;
    private final ObjectMapper objectMapper;
    private final PrintStream output;
    private final PrintStream error;

    public ClusterComparisonCoordinator() {
        this(new UriComparisonDescriptorOpener(), new ConfigurationManager(), new CompareRequestFactory(),
                new DefaultCompareRuntime(), new ObjectMapper(), System.out, System.err);
    }

    ClusterComparisonCoordinator(ComparisonDescriptorOpener descriptorOpener,
                                 ConfigurationManager configurationManager,
                                 CompareRequestFactory compareRequestFactory,
                                 CompareRuntime compareRuntime,
                                 ObjectMapper objectMapper,
                                 PrintStream output,
                                 PrintStream error) {
        this.descriptorOpener = Objects.requireNonNull(descriptorOpener, "descriptorOpener");
        this.configurationManager = Objects.requireNonNull(configurationManager, "configurationManager");
        this.compareRequestFactory = Objects.requireNonNull(compareRequestFactory, "compareRequestFactory");
        this.compareRuntime = Objects.requireNonNull(compareRuntime, "compareRuntime");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
    }

    public static void main(String[] args) {
        int exitCode = new ClusterComparisonCoordinator().run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public int run(String[] args) {
        if (args == null || (args.length != 2 && args.length != 3)) {
            error.println("Cluster comparison failed.");
            return 2;
        }
        try {
            ComparisonDescriptor descriptor = descriptorOpener.open(args[1]);
            CliConfiguration configuration;
            try (InputStream inputStream = descriptor.open()) {
                configuration = configurationManager(args).loadConfiguration(inputStream, descriptor.format());
            }
            CompareRequest request = compareRequestFactory.create(configuration);
            DiffResult result = compareRuntime.execute(request);
            output.println(objectMapper.writeValueAsString(summary(args[0], result)));
            return 0;
        } catch (Exception e) {
            error.println("Cluster comparison failed.");
            return 1;
        }
    }

    private Map<String, Object> summary(String submissionId, DiffResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("submissionId", submissionId);
        summary.put("statistics", result.getStatisticsMap());
        return summary;
    }

    private ConfigurationManager configurationManager(String[] args) throws Exception {
        if (args.length == 2) {
            return configurationManager;
        }
        Properties secretEnvironment = new Properties();
        try (InputStream inputStream = Files.newInputStream(Path.of(args[2]))) {
            secretEnvironment.load(inputStream);
        }
        Map<String, String> environment = new LinkedHashMap<>(System.getenv());
        secretEnvironment.forEach((name, value) -> environment.put(String.valueOf(name), String.valueOf(value)));
        return new ConfigurationManager(environment);
    }
}

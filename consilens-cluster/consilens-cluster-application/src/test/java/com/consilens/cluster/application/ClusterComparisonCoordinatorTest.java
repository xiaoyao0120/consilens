package com.consilens.cluster.application;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.diff.DiffResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterComparisonCoordinatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldExecuteOneComparisonFromLocalizedDescriptorAndPrintRedactedSummary() throws Exception {
        String password = "password-that-must-not-be-printed";
        Path descriptor = temporaryDirectory.resolve("comparison.yaml");
        Files.writeString(descriptor, configuration(password));
        AtomicReference<CompareRequest> captured = new AtomicReference<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        ClusterComparisonCoordinator coordinator = coordinator(captured, output, error);

        int exitCode = coordinator.run(new String[]{"submission-42", descriptor.toString()});

        String summary = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(summary.contains("submission-42"));
        assertTrue(summary.contains("differenceCount"));
        assertFalse(summary.contains(password));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
        assertEquals("mysql", captured.get().getSource().getType());
        assertEquals("mysql", captured.get().getTarget().getType());
    }

    @Test
    void shouldRejectUnsupportedDescriptorSchemeWithoutLeakingReference() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        ClusterComparisonCoordinator coordinator = coordinator(new AtomicReference<>(), output, error);

        int exitCode = coordinator.run(new String[]{"submission-42", "s3a://bucket/secret-descriptor.yaml"});

        assertEquals(1, exitCode);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertEquals("Cluster comparison failed.\n", error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void shouldResolveDescriptorEnvironmentFromLocalizedSecretProperties() throws Exception {
        Path descriptor = temporaryDirectory.resolve("comparison.yaml");
        Path secretProperties = temporaryDirectory.resolve("submission-secrets.properties");
        Files.writeString(descriptor, configuration("${env.SOURCE_PASSWORD}"));
        Files.writeString(secretProperties, "SOURCE_PASSWORD=resolved-on-yarn-node\n");
        AtomicReference<CompareRequest> captured = new AtomicReference<>();
        ClusterComparisonCoordinator coordinator = coordinator(captured, new ByteArrayOutputStream(),
                new ByteArrayOutputStream());

        int exitCode = coordinator.run(new String[]{"submission-42", descriptor.toString(), secretProperties.toString()});

        assertEquals(0, exitCode);
        assertEquals("resolved-on-yarn-node", captured.get().getSource().getConnection().get("password"));
        assertEquals("resolved-on-yarn-node", captured.get().getTarget().getConnection().get("password"));
    }

    @Test
    void shouldRejectInvalidArgumentsWithFixedError() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        ClusterComparisonCoordinator coordinator = coordinator(new AtomicReference<>(), new ByteArrayOutputStream(), error);

        int exitCode = coordinator.run(new String[]{"submission-42"});

        assertEquals(2, exitCode);
        assertEquals("Cluster comparison failed.\n", error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void shouldReportSuccessToStatusReporterWithPrintedSummary() throws Exception {
        Path descriptor = temporaryDirectory.resolve("comparison.yaml");
        Files.writeString(descriptor, configuration("password"));
        RecordingStatusReporter reporter = new RecordingStatusReporter();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ClusterComparisonCoordinator coordinator = new ClusterComparisonCoordinator(
                new UriComparisonDescriptorOpener(), new ConfigurationManager(), new CompareRequestFactory(),
                request -> DiffResult.of(List.of(), TablePath.of("source_orders"), TablePath.of("target_orders")),
                new ObjectMapper(), new PrintStream(output), new PrintStream(new ByteArrayOutputStream()),
                reporter);

        int exitCode = coordinator.run(new String[]{"submission-42", descriptor.toString()});

        assertEquals(0, exitCode);
        assertTrue(reporter.started);
        assertNull(reporter.failure);
        assertTrue(reporter.success.contains("submission-42"));
        assertEquals(output.toString(StandardCharsets.UTF_8).trim(), reporter.success);
    }

    @Test
    void shouldReportFailureToStatusReporterWithoutLeakingCredentials() throws Exception {
        Path descriptor = temporaryDirectory.resolve("comparison.yaml");
        Files.writeString(descriptor, configuration("secret-password"));
        RecordingStatusReporter reporter = new RecordingStatusReporter();
        ClusterComparisonCoordinator coordinator = new ClusterComparisonCoordinator(
                new RecordingUriOpener(), new ConfigurationManager(), new CompareRequestFactory(),
                request -> DiffResult.of(List.of(), TablePath.of("source_orders"), TablePath.of("target_orders")),
                new ObjectMapper(), new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()), reporter);

        int exitCode = coordinator.run(new String[]{"submission-42", descriptor.toString()});

        assertEquals(1, exitCode);
        assertTrue(reporter.started);
        assertNull(reporter.success);
        assertTrue(reporter.failure.startsWith("Cluster comparison failed: "));
        assertFalse(reporter.failure.contains("secret-password"));
        assertFalse(reporter.failure.contains(descriptor.toString()));
    }

    private static final class RecordingUriOpener implements ComparisonDescriptorOpener {
        @Override
        public ComparisonDescriptor open(String reference) {
            throw new IllegalArgumentException("rejected: " + reference.getClass().getSimpleName());
        }
    }

    private static final class RecordingStatusReporter implements ApplicationStatusReporter {
        private boolean started;
        private String success;
        private String failure;

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void reportSucceeded(String summary) {
            success = summary;
        }

        @Override
        public void reportFailed(String diagnostics) {
            failure = diagnostics;
        }
    }

    private ClusterComparisonCoordinator coordinator(AtomicReference<CompareRequest> captured,
                                                       ByteArrayOutputStream output,
                                                       ByteArrayOutputStream error) {
        return new ClusterComparisonCoordinator(new UriComparisonDescriptorOpener(), new ConfigurationManager(),
                new CompareRequestFactory(), request -> {
                    captured.set(request);
                    return DiffResult.of(List.of(), TablePath.of("source_orders"), TablePath.of("target_orders"));
                }, new ObjectMapper(), new PrintStream(output), new PrintStream(error));
    }

    private String configuration(String password) {
        return "source:\n"
                + "  type: mysql\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/source_db\n"
                + "    username: source_user\n"
                + "    password: " + password + "\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: source_orders\n"
                + "target:\n"
                + "  type: mysql\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/target_db\n"
                + "    username: target_user\n"
                + "    password: " + password + "\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: target_orders\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n"
                + "  fields:\n"
                + "    source: [value]\n"
                + "    target: [value]\n"
                + "strategy:\n"
                + "  mode: checksum\n"
                + "  algorithm: concat\n"
                + "  bisectionFactor: 4\n"
                + "  bisectionThreshold: 1000\n"
                + "  batchSize: 100\n"
                + "  localCompare:\n"
                + "    mode: full\n";
    }
}

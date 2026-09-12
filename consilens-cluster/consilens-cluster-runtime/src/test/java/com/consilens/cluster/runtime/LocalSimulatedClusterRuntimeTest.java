package com.consilens.cluster.runtime;

import com.consilens.connector.api.planner.ClusterExecutionSpec;
import com.consilens.connector.api.planner.ExecutionMode;
import com.consilens.core.diff.DiffResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSimulatedClusterRuntimeTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldRetryFailedSplitAndOnlyMergeItsSuccessfulAttempt() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        LocalSplitTask retryingTask = task("split-1", () -> {
            if (executions.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary failure");
            }
            return result(2, 2, 1);
        });

        LocalSimulationResult result = new LocalSimulatedClusterRuntime().execute(request(
                List.of(retryingTask, task("split-2", () -> result(3, 3, 2))), 2));

        assertEquals(2, executions.get());
        assertEquals(5, result.getDiffResult().getStatistics().getSourceRowCount());
        assertEquals(5, result.getDiffResult().getStatistics().getTargetRowCount());
        assertEquals(3, result.getDiffResult().getStatistics().getTotalDifferences());
        assertEquals(List.of(SplitAttemptState.FAILED, SplitAttemptState.SUCCEEDED, SplitAttemptState.SUCCEEDED),
                result.getAttempts().stream().map(SplitAttemptRecord::getState).collect(Collectors.toList()));

        JsonNode manifest = new ObjectMapper().readTree(result.getManifestPath().toFile());
        assertEquals("LOCAL", manifest.path("executionMode").asText());
        assertEquals(3, manifest.path("totalDifferences").asLong());
        assertEquals(2, manifest.path("attempts").size());
        assertTrue(manifest.path("attempts").findValuesAsText("state").stream()
                .allMatch(SplitAttemptState.SUCCEEDED.name()::equals));
        try (Stream<Path> paths = Files.list(temporaryDirectory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void shouldRejectNonLocalExecutionModeWithoutRunningTasks() {
        AtomicInteger executions = new AtomicInteger();
        LocalSimulationRequest request = LocalSimulationRequest.builder()
                .submissionId("submission-1")
                .executionSpec(ClusterExecutionSpec.builder().executionMode(ExecutionMode.YARN).build())
                .splitTasks(List.of(task("split-1", () -> {
                    executions.incrementAndGet();
                    return result(1, 1, 0);
                })))
                .manifestDirectory(temporaryDirectory)
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new LocalSimulatedClusterRuntime().execute(request));

        assertTrue(exception.getMessage().contains("LOCAL"));
        assertEquals(0, executions.get());
        assertFalse(Files.exists(temporaryDirectory.resolve("manifest.json")));
    }

    @Test
    void shouldNotPublishManifestWhenRetriesAreExhausted() {
        LocalSimulationRequest request = request(List.of(task("split-1", () -> {
            throw new IllegalStateException("permanent failure");
        })), 2);

        LocalClusterExecutionException exception = assertThrows(LocalClusterExecutionException.class,
                () -> new LocalSimulatedClusterRuntime().execute(request));

        assertTrue(exception.getMessage().contains("2 attempt"));
        assertFalse(Files.exists(temporaryDirectory.resolve("manifest.json")));
    }

    @Test
    void shouldKeepCredentialsOutOfManifest() throws Exception {
        LocalSimulationRequest request = LocalSimulationRequest.builder()
                .submissionId("submission-1")
                .executionSpec(ClusterExecutionSpec.builder()
                        .executionMode(ExecutionMode.LOCAL)
                        .attributes(Map.of("password", "do-not-persist"))
                        .build())
                .splitTasks(List.of(task("split-1", () -> result(1, 1, 0))))
                .manifestDirectory(temporaryDirectory)
                .build();

        LocalSimulationResult result = new LocalSimulatedClusterRuntime().execute(request);

        assertFalse(Files.readString(result.getManifestPath()).contains("do-not-persist"));
    }

    private LocalSimulationRequest request(List<LocalSplitTask> tasks, int maximumAttempts) {
        return LocalSimulationRequest.builder()
                .submissionId("submission-1")
                .executionSpec(ClusterExecutionSpec.builder()
                        .executionMode(ExecutionMode.LOCAL)
                        .maxAttempts(maximumAttempts)
                        .build())
                .splitTasks(tasks)
                .manifestDirectory(temporaryDirectory)
                .build();
    }

    private LocalSplitTask task(String splitId, ThrowingResultSupplier supplier) {
        return new LocalSplitTask() {
            @Override
            public String getSplitId() {
                return splitId;
            }

            @Override
            public DiffResult execute() throws Exception {
                return supplier.get();
            }
        };
    }

    private DiffResult result(long sourceRows, long targetRows, long mismatchCount) {
        return DiffResult.builder()
                .differences(List.of())
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(sourceRows)
                        .targetRowCount(targetRows)
                        .sourceMissingCount(0)
                        .targetMissingCount(0)
                        .mismatchCount(mismatchCount)
                        .totalDifferences(mismatchCount)
                        .processingTimeMs(1)
                        .unchangedCount(Math.min(sourceRows, targetRows) - mismatchCount)
                        .differencePercentage(0.0)
                        .build())
                .metadata(Map.of())
                .build();
    }

    @FunctionalInterface
    private interface ThrowingResultSupplier {
        DiffResult get() throws Exception;
    }
}

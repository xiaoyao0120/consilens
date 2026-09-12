package com.consilens.cluster.runtime;

import com.consilens.connector.api.planner.ClusterExecutionSpec;
import com.consilens.connector.api.planner.ExecutionMode;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-process implementation of the Coordinator/Worker protocol used by Phase 0.
 *
 * <p>Tasks are intentionally executed in input order. This keeps retry and manifest
 * behavior deterministic while the production YARN and Kubernetes backends do not exist.
 */
public class LocalSimulatedClusterRuntime {

    private static final int DEFAULT_MAXIMUM_ATTEMPTS = 1;

    private final LocalManifestPublisher manifestPublisher;

    public LocalSimulatedClusterRuntime() {
        this(new LocalManifestPublisher());
    }

    LocalSimulatedClusterRuntime(LocalManifestPublisher manifestPublisher) {
        this.manifestPublisher = Objects.requireNonNull(manifestPublisher, "manifestPublisher");
    }

    public LocalSimulationResult execute(LocalSimulationRequest request) throws Exception {
        validateRequest(request);

        int maximumAttempts = maximumAttempts(request.getExecutionSpec());
        List<SplitAttemptRecord> attemptRecords = new ArrayList<>();
        List<DiffResult> successfulResults = new ArrayList<>();
        LinkedHashSet<String> successfulSplitIds = new LinkedHashSet<>();

        for (LocalSplitTask task : request.getSplitTasks()) {
            DiffResult successfulResult = executeSplit(task, maximumAttempts, attemptRecords);
            successfulResults.add(successfulResult);
            successfulSplitIds.add(task.getSplitId());
        }

        DiffResult mergedResult = mergeSuccessfulResults(successfulResults);
        List<SplitAttemptRecord> successfulAttemptRecords = new ArrayList<>();
        for (SplitAttemptRecord attemptRecord : attemptRecords) {
            if (attemptRecord.getState() == SplitAttemptState.SUCCEEDED) {
                successfulAttemptRecords.add(attemptRecord);
            }
        }
        LocalExecutionManifest manifest = LocalExecutionManifest.builder()
                .formatVersion("1")
                .submissionId(request.getSubmissionId())
                .executionMode(ExecutionMode.LOCAL)
                .attempts(List.copyOf(successfulAttemptRecords))
                .successfulSplitIds(List.copyOf(successfulSplitIds))
                .sourceRowCount(mergedResult.getStatistics().getSourceRowCount())
                .targetRowCount(mergedResult.getStatistics().getTargetRowCount())
                .totalDifferences(mergedResult.getStatistics().getTotalDifferences())
                .build();
        Path manifestPath = manifestPublisher.publish(request.getManifestDirectory(), manifest);
        return LocalSimulationResult.builder()
                .diffResult(mergedResult)
                .manifestPath(manifestPath)
                .attempts(List.copyOf(attemptRecords))
                .build();
    }

    private DiffResult executeSplit(LocalSplitTask task,
                                    int maximumAttempts,
                                    List<SplitAttemptRecord> attemptRecords) throws Exception {
        Throwable lastFailure = null;
        for (int attemptNumber = 1; attemptNumber <= maximumAttempts; attemptNumber++) {
            SplitAttempt attempt = new SplitAttempt(task.getSplitId(), attemptNumber);
            attempt.start();
            try {
                DiffResult result = task.execute();
                if (result == null) {
                    throw new IllegalStateException("Split task returned no DiffResult");
                }
                attempt.succeed();
                attemptRecords.add(toRecord(attempt));
                return result;
            } catch (Exception e) {
                attempt.fail(e);
                attemptRecords.add(toRecord(attempt));
                lastFailure = e;
            }
        }
        throw new LocalClusterExecutionException("Split " + task.getSplitId()
                + " failed after " + maximumAttempts + " attempt(s)", lastFailure);
    }

    private SplitAttemptRecord toRecord(SplitAttempt attempt) {
        return SplitAttemptRecord.builder()
                .splitId(attempt.getSplitId())
                .attemptNumber(attempt.getAttemptNumber())
                .state(attempt.getState())
                .failureMessage(attempt.getFailureMessage())
                .build();
    }

    private void validateRequest(LocalSimulationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.getSubmissionId() == null || request.getSubmissionId().trim().isEmpty()) {
            throw new IllegalArgumentException("submissionId must not be blank");
        }
        if (request.getExecutionSpec() == null
                || request.getExecutionSpec().getExecutionMode() != ExecutionMode.LOCAL) {
            throw new IllegalArgumentException("Local simulated runtime only supports LOCAL execution mode");
        }
        if (request.getSplitTasks() == null || request.getSplitTasks().isEmpty()) {
            throw new IllegalArgumentException("splitTasks must not be empty");
        }
        if (request.getManifestDirectory() == null) {
            throw new IllegalArgumentException("manifestDirectory must not be null");
        }
        LinkedHashSet<String> splitIds = new LinkedHashSet<>();
        for (LocalSplitTask task : request.getSplitTasks()) {
            if (task == null || task.getSplitId() == null || task.getSplitId().trim().isEmpty()) {
                throw new IllegalArgumentException("each split task must have a splitId");
            }
            if (!splitIds.add(task.getSplitId())) {
                throw new IllegalArgumentException("splitIds must be unique: " + task.getSplitId());
            }
        }
        maximumAttempts(request.getExecutionSpec());
    }

    private int maximumAttempts(ClusterExecutionSpec executionSpec) {
        Integer configured = executionSpec.getMaxAttempts();
        int value = configured == null ? DEFAULT_MAXIMUM_ATTEMPTS : configured;
        if (value < 1) {
            throw new IllegalArgumentException("maximumAttempts must be positive");
        }
        return value;
    }

    private DiffResult mergeSuccessfulResults(List<DiffResult> results) {
        List<DiffRow> differences = new ArrayList<>();
        long sourceRowCount = 0;
        long targetRowCount = 0;
        long sourceMissingCount = 0;
        long targetMissingCount = 0;
        long mismatchCount = 0;
        long processingTimeMs = 0;
        long unchangedCount = 0;
        Map<String, Object> metadata = new LinkedHashMap<>();
        DiffResult first = null;
        Instant completedAt = Instant.EPOCH;

        for (DiffResult result : results) {
            if (first == null) {
                first = result;
            }
            if (result.getDifferences() != null) {
                differences.addAll(result.getDifferences());
            }
            DiffResult.DiffStatistics statistics = result.getStatistics();
            if (statistics != null) {
                sourceRowCount += statistics.getSourceRowCount();
                targetRowCount += statistics.getTargetRowCount();
                sourceMissingCount += statistics.getSourceMissingCount();
                targetMissingCount += statistics.getTargetMissingCount();
                mismatchCount += statistics.getMismatchCount();
                processingTimeMs += statistics.getProcessingTimeMs();
                unchangedCount += statistics.getUnchangedCount();
            }
            if (result.getMetadata() != null) {
                metadata.putAll(result.getMetadata());
            }
            if (result.getCompletedAt() != null && result.getCompletedAt().isAfter(completedAt)) {
                completedAt = result.getCompletedAt();
            }
        }

        if (first == null) {
            throw new IllegalArgumentException("results must not be empty");
        }
        return DiffResult.builder()
                .differences(differences)
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(sourceRowCount)
                        .targetRowCount(targetRowCount)
                        .sourceMissingCount(sourceMissingCount)
                        .targetMissingCount(targetMissingCount)
                        .mismatchCount(mismatchCount)
                        .totalDifferences(sourceMissingCount + targetMissingCount + mismatchCount)
                        .processingTimeMs(processingTimeMs)
                        .unchangedCount(unchangedCount)
                        .differencePercentage(0.0)
                        .build())
                .infoTree(Optional.empty())
                .completedAt(completedAt.equals(Instant.EPOCH) ? Instant.now() : completedAt)
                .metadata(metadata)
                .sourceTablePath(first.getSourceTablePath())
                .targetTablePath(first.getTargetTablePath())
                .build();
    }
}

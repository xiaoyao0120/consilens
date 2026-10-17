package com.consilens.sink.api;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.model.ResultConfig;
import com.consilens.sink.api.model.SinkConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the lifecycle of all Sink instances and routes events by type.
 */
@Slf4j
public class SinkManager {

    private final List<Sink> diffRecordSinks = new ArrayList<>();
    private final List<Sink> resultSinks = new ArrayList<>();
    private final SinkRegistry registry = new SinkRegistry();
    private final ResultConfig config;

    public SinkManager(ResultConfig config) {
        this.config = config;
    }

    public void open(DiffContext context) throws Exception {
        if (config == null || config.getSinks() == null) {
            return;
        }
        for (SinkConfig sinkConfig : config.getSinks()) {
            if (!sinkConfig.isEnabled()) {
                continue;
            }
            try {
                Sink sink = registry.create(sinkConfig.getFormat(), sinkConfig.getType());
                sink.open(sinkConfig, context);
                if (SinkTypes.DIFF_RECORD.equalsIgnoreCase(sinkConfig.getType())) {
                    diffRecordSinks.add(sink);
                } else if (SinkTypes.RESULT.equalsIgnoreCase(sinkConfig.getType())) {
                    resultSinks.add(sink);
                }
            } catch (Exception exception) {
                handleSinkFailure(exception);
            }
        }
    }

    public void onDiffRecords(List<DiffRow> rows, DiffContext context) {
        diffRecordSinks.forEach(sink -> safeRun(() -> sink.onDiffRecords(rows, context)));
    }

    public void onResult(DiffResult result, DiffContext context) {
        resultSinks.forEach(sink -> safeRun(() -> sink.onResult(result, context)));
    }

    public void onError(DiffContext context, Throwable error) {
        resultSinks.forEach(sink -> safeRun(() -> sink.onError(context, error)));
    }

    public void close() {
        diffRecordSinks.forEach(sink -> safeRun(sink::close));
        resultSinks.forEach(sink -> safeRun(sink::close));
    }

    public long diffRecordCount() {
        return diffRecordSinks.stream()
                .mapToLong(Sink::writtenRecordCount)
                .filter(count -> count >= 0)
                .sum();
    }

    public boolean diffRecordTruncated() {
        return diffRecordSinks.stream().anyMatch(Sink::isTruncated);
    }

    private void safeRun(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            handleSinkFailure(e);
        }
    }

    private void handleSinkFailure(Exception exception) {
        if (config != null && config.isFailOnSinkError()) {
            throw new RuntimeException("Sink execution failed", exception);
        }
        log.warn("Sink execution failed: {}", exception.getMessage(), exception);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

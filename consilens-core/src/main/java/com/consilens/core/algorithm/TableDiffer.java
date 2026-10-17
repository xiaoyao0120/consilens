package com.consilens.core.algorithm;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.common.enums.LocalCompareMode;
import com.consilens.core.segment.TableSegment;
import com.consilens.core.diff.DiffEmitter;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffSink;
import com.consilens.core.diff.InfoTreeRecorder;
import com.consilens.core.diff.InMemoryDiffSink;
import com.consilens.core.thread.ConcurrencyConfig;
import com.consilens.core.thread.ExecutorProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;

/**
 * Provides the foundation for different diff algorithms.
 */
@Slf4j
public abstract class TableDiffer implements DiffEmitter {

    protected final ExecutorProvider executorProvider;
    protected final DifferConfig config;
    protected final boolean ownsExecutorProvider;
    private DiffSink diffSink;

    protected TableDiffer(DifferConfig config) {
        this(config, new ExecutorProvider(config.getConcurrencyConfig()), true);
    }

    protected TableDiffer(DifferConfig config, ExecutorProvider executorProvider) {
        this(config, executorProvider, false);
    }

    protected TableDiffer(DifferConfig config, ExecutorProvider executorProvider, boolean ownsExecutorProvider) {
        this.config = config;
        this.executorProvider = executorProvider;
        this.ownsExecutorProvider = ownsExecutorProvider;
        this.diffSink = new InMemoryDiffSink();
    }

    @Override
    public void setDiffSink(DiffSink diffSink) {
        this.diffSink = diffSink != null ? diffSink : DiffSink.noop();
    }

    @Override
    public DiffSink getDiffSink() {
        return diffSink;
    }

    protected List<DiffRow> getCollectedDifferences() {
        if (diffSink instanceof InMemoryDiffSink) {
            return ((InMemoryDiffSink) diffSink).getDifferences();
        }
        return new ArrayList<>();
    }

    protected void ensureDiffLimit(long nextDifferenceCount) {
        if (diffSink instanceof InMemoryDiffSink) {
            long current = ((InMemoryDiffSink) diffSink).size();
            long maxDifferences = config.getMaxDifferences();
            if (current + nextDifferenceCount > maxDifferences) {
                throw new IllegalStateException("Diff result exceeds maxDifferences=" + maxDifferences
                        + ". Increase strategy.maxDifferences or narrow the comparison scope.");
            }
        }
    }

    /**
     * Main entry point for table diffing.
     */
    public CompletableFuture<DiffResult> diffTables(TableSegment table1, TableSegment table2) {
        log.info("Starting diff between {} and {}", table1.getTablePath(), table2.getTablePath());

        try {
            // Align schemas between the two segments before any expensive work
            validateAndAdjustColumns(table1, table2);

            // 严格档（validateUniqueKeys=true，非默认）：键必须唯一。重复键下 join 会 N×M 倍增、
            // checksum 的归因会退化成整键 SM+TM，都属于"按键比对不成立"，必须在开始前明确失败并给出证据。
            if (config.isValidateUniqueKeys()) {
                DuplicateKeyGuard.assertUnique(table1, table2);
            }

            // Build the base info tree that the concrete algorithms can enrich
            InfoTreeRecorder infoTreeRecorder = new InfoTreeRecorder("diff");
            infoTreeRecorder.startNode("diff", null, "diff", "root", "diff", 0);
            infoTreeRecorder.recordGlobalMetric("algorithm", getClass().getSimpleName());
            infoTreeRecorder.recordGlobalMetric("startedAt", Instant.now().toString());

            CompletableFuture<DiffResult> future = diffTablesRoot(table1, table2, infoTreeRecorder);
            return future.whenComplete((result, error) -> {
                        infoTreeRecorder.endNode("diff");
                        infoTreeRecorder.recordGlobalMetric("endedAt", Instant.now().toString());
                        infoTreeRecorder.endTree();
                    })
                    .thenApply(result -> {
                        if (result == null) {
                            return null;
                        }
                        return result.toBuilder()
                                .infoTree(Optional.of(infoTreeRecorder.snapshot()))
                                .build();
                    });

        } catch (Exception e) {
            log.error("Error during table diffing", e);
            // 唯一键校验失败等"业务性拒绝"要保留原始信息（键与两侧行数），
            // 不能被 "Table diff failed" 包一层导致明细丢失。
            if (e instanceof com.consilens.connector.api.ConnectorException) {
                return CompletableFuture.failedFuture(e);
            }
            return CompletableFuture.failedFuture(new RuntimeException("Table diff failed", e));
        }
    }

    /**
     * Root diff method that delegates to specific algorithm implementation.
     */
    protected abstract CompletableFuture<DiffResult> diffTablesRoot(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder);

    /**
     * Diff specific segments - core algorithm implementation point.
     */
    protected abstract CompletableFuture<Void> diffSegments(
            TableSegment table1,
            TableSegment table2,
            InfoTreeRecorder infoTreeRecorder,
            long maxRows,
            int level);

    /**
     * Validate table schemas and adjust for compatibility.
     * Supports tables with different column names but same structure.
     */
    protected void validateAndAdjustColumns(TableSegment table1, TableSegment table2) {
        // Validate key columns count match (names can be different, but count must match)
        if (table1.getKeyColumns().size() != table2.getKeyColumns().size()) {
            log.warn("Table1 key columns: {}, Table2 key columns: {}",
                     table1.getKeyColumns(), table2.getKeyColumns());
            throw new IllegalArgumentException(String.format(
                "Key columns count must match between tables. Table1 has %d, Table2 has %d",
                table1.getKeyColumns().size(), table2.getKeyColumns().size()));
        }

        /*
         * Extra (non-key) columns can differ, but warn when the counts do not align. Algorithms depend on
         * positional comparisons (e.g., LocalDiffEngine) so a mismatch is usually accidental. We still
         * continue to allow asymmetric comparisons, but the log warning draws attention to potential
         * misconfiguration.
         */
        if (table1.getExtraColumns().size() != table2.getExtraColumns().size()) {
            log.warn("Table1 extra columns: {}, Table2 extra columns: {}",
                     table1.getExtraColumns(), table2.getExtraColumns());
            log.info("Extra columns count difference is acceptable for comparison");
            // Don't throw exception - extra columns can be different and still work
        }

        log.info("Column validation completed for tables {} and {} (key columns: {}, extra columns: {} vs {})",
                table1.getTablePath(), table2.getTablePath(),
                table1.getKeyColumns().size(), table1.getExtraColumns().size(), table2.getExtraColumns().size());
    }

    /**
     * Shutdown resources and clean up.
     */
    public void shutdown() {
        try {
            log.info("Shutting down TableDiffer...");
            if (!ownsExecutorProvider) {
                log.debug("Skipping ExecutorProvider shutdown because TableDiffer does not own it");
                return;
            }
            if (executorProvider != null) {
                executorProvider.shutdown();
                log.info("ExecutorProvider shutdown completed");
            }
        } catch (Exception e) {
            log.warn("Error during TableDiffer shutdown", e);
        }
    }

    /**
     * Length-prefix key parts so segment identifiers stay unambiguous and collision-free.
     */
    protected static String encodeKeyParts(String... parts) {
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            String value = part != null ? part : "null";
            key.append(value.length()).append(':').append(value).append('|');
        }
        return key.toString();
    }

    /**
     * Configuration for diff algorithms.
     */
    @Getter
    public static class DifferConfig {
        private final int bisectionFactor;
        private final long bisectionThreshold;
        private final int maxDepth;
        private final ChecksumAlgorithm checksumAlgorithm;
        private final LocalCompareMode localCompareMode;
        private final ConcurrencyConfig concurrencyConfig;
        private final long maxDifferences;
        private final boolean validateUniqueKeys;

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm,
                           LocalCompareMode localCompareMode,
                           ConcurrencyConfig concurrencyConfig,
                           long maxDifferences, boolean validateUniqueKeys) {
            this.bisectionFactor = bisectionFactor;
            this.bisectionThreshold = bisectionThreshold;
            this.maxDepth = 10;
            this.checksumAlgorithm = checksumAlgorithm != null ? checksumAlgorithm : ChecksumAlgorithm.CONCAT;
            this.localCompareMode = localCompareMode != null ? localCompareMode : LocalCompareMode.FULL;
            this.concurrencyConfig = concurrencyConfig != null ? concurrencyConfig : ConcurrencyConfig.defaultConfig();
            this.maxDifferences = maxDifferences > 0 ? maxDifferences : 1_000_000L;
            this.validateUniqueKeys = validateUniqueKeys;
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm,
                           LocalCompareMode localCompareMode,
                           ConcurrencyConfig concurrencyConfig,
                           long maxDifferences) {
            this(bisectionFactor, bisectionThreshold, checksumAlgorithm,
                 localCompareMode, concurrencyConfig, maxDifferences, false);
        }

        /** 严格档开关：true 时比对前做唯一键探针，命中重复键直接失败并列出键与行数。 */
        public boolean isValidateUniqueKeys() {
            return validateUniqueKeys;
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm,
                           LocalCompareMode localCompareMode,
                           ConcurrencyConfig concurrencyConfig) {
            this(bisectionFactor, bisectionThreshold,
                 checksumAlgorithm, localCompareMode, concurrencyConfig, 1_000_000L);
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm) {
            this(bisectionFactor, bisectionThreshold,
                 checksumAlgorithm, LocalCompareMode.FULL, ConcurrencyConfig.defaultConfig());
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm,
                           ConcurrencyConfig concurrencyConfig) {
            this(bisectionFactor, bisectionThreshold,
                 checksumAlgorithm, LocalCompareMode.FULL, concurrencyConfig);
        }

        public static DifferConfig defaultConfig() {
            return new DifferConfig(32, 16384, ChecksumAlgorithm.CONCAT,
                    LocalCompareMode.FULL, ConcurrencyConfig.defaultConfig());
        }

    }
}

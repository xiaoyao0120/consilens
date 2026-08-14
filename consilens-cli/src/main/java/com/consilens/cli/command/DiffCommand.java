package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.CliDiffResult;
import com.consilens.cli.service.DiffService;
import com.consilens.cli.service.SensitiveValueMasker;
import com.consilens.core.diff.DiffResult;

import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Comparator;

/**
 * Picocli subcommand for performing data comparison (diff) operations.
 */
@Slf4j
@Command(
    name = "diff",
    description = "Perform data comparison between source and target databases",
    mixinStandardHelpOptions = true
)
public class DiffCommand implements Runnable {

    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file path")
    private String configFile;

    @Option(names = {"--dry-run"}, description = "Perform a dry run without actual comparison")
    private boolean dryRun;

    @Option(names = {"--verbose"}, description = "Enable verbose logging")
    private boolean verbose;

    @Option(names = {"--benchmark-metrics"}, hidden = true,
            description = "Emit machine-readable benchmark metrics")
    private boolean benchmarkMetrics;

    @Override
    public void run() {
        try {
            ConfigurationManager configurationManager = new ConfigurationManager();
            DiffService diffService = new DiffService();

            CliConfiguration config = configurationManager.loadConfiguration(configFile);

            if (verbose) {
                log.info("Starting diff operation with configuration:");
                log.info("  Strategy: {}", config.getStrategyMode());
                log.info("  Algorithm: {}", config.getAlgorithm());
                log.info("  Source: {}", SensitiveValueMasker.maskJdbcUrl(config.getSource().getUrl()));
                log.info("  Source Resource: {}", resourceDisplay(config.getSource()));
                log.info("  Target: {}", SensitiveValueMasker.maskJdbcUrl(config.getTarget().getUrl()));
                log.info("  Target Resource: {}", resourceDisplay(config.getTarget()));
                log.info("  Source Key Columns: {}", config.getComparison().getKeys().getSource());
                log.info("  Target Key Columns: {}", config.getComparison().getKeys().getTarget());
                if (config.getComparison().getFields() != null
                        && config.getComparison().getFields().getSource() != null
                        && !config.getComparison().getFields().getSource().isEmpty()) {
                    log.info("  Source Fields: {}", config.getComparison().getFields().getSource());
                }
                if (config.getComparison().getFields() != null
                        && config.getComparison().getFields().getTarget() != null
                        && !config.getComparison().getFields().getTarget().isEmpty()) {
                    log.info("  Target Fields: {}", config.getComparison().getFields().getTarget());
                }
                log.info("  Concurrency: {}", config.getConcurrency());
                log.info("  Batch Size: {}", config.getStrategy().getBatchSize());
            }

            CliDiffResult result;
            if (dryRun) {
                log.info("Performing dry run (validation only)...");
                result = diffService.performDryRun(config);
            } else {
                log.info("Starting diff operation...");
                log.info("This may take a while depending on table sizes and strategy chosen.");

                long startTime = System.currentTimeMillis();
                result = diffService.performDiff(config);
                long totalTime = System.currentTimeMillis() - startTime;

                log.info("Diff operation completed in {} ms", totalTime);
            }

            displayDiffResults(result, dryRun);
            if (benchmarkMetrics && !dryRun) {
                displayBenchmarkMetrics(result);
            }

        } catch (Exception e) {
            log.error("Diff operation failed", e);
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private void displayBenchmarkMetrics(CliDiffResult result) {
        benchmarkMetric("totalDifferences", result.getTotalDifferences());
        benchmarkMetric("sourceMissingRows", result.getSourceMissingCount());
        benchmarkMetric("targetMissingRows", result.getTargetMissingCount());
        benchmarkMetric("mismatchedRows", result.getMismatchCount());
        benchmarkMetric("heapPeakBytes", heapPeakBytes());

        if (result.getInfoTree() == null) {
            return;
        }
        DiffResult.InfoTree tree = result.getInfoTree();
        benchmarkMetric("segmentCount", tree.getTotalSegments());
        benchmarkMetric("maxDepth", tree.getMaxDepth());
        benchmarkMetric("logicalRowsScanned", tree.getTotalRowsScanned());
        benchmarkMetric("instrumentedQueryCount", tree.getNodes().stream()
                .mapToLong(DiffResult.InfoTreeNode::getQueryCount).sum());
        benchmarkMetric("rowsFetched", tree.getNodes().stream()
                .mapToLong(DiffResult.InfoTreeNode::getRowsFetched).sum());
        benchmarkMetric("resultBytesFetchedEstimate", tree.getNodes().stream()
                .mapToLong(DiffResult.InfoTreeNode::getBytesFetched).sum());
        Object firstDifferenceAt = tree.getMetrics() == null ? null : tree.getMetrics().get("firstDifferenceAt");
        if (firstDifferenceAt instanceof Number) {
            benchmarkMetric("firstDifferenceObservedMs",
                    Math.max(0L, ((Number) firstDifferenceAt).longValue() - tree.getStartTime()));
            return;
        }
        tree.getNodes().stream()
                .filter(node -> node.getDiffCount() > 0 && node.getEndedAt() > 0)
                .min(Comparator.comparingLong(DiffResult.InfoTreeNode::getEndedAt))
                .ifPresent(node -> benchmarkMetric("firstDifferenceObservedMs",
                        Math.max(0L, node.getEndedAt() - tree.getStartTime())));
    }

    private void benchmarkMetric(String name, long value) {
        System.out.println("BENCHMARK_METRIC " + name + "=" + value);
    }

    private long heapPeakBytes() {
        long peak = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                peak += Math.max(0L, pool.getPeakUsage().getUsed());
            }
        }
        return peak;
    }

    private void displayDiffResults(CliDiffResult result, boolean isDryRun) {
        log.info("Diff Results" + (isDryRun ? " (Dry Run)" : "") + ":");
        log.info("=".repeat(50));
        log.info("Strategy: " + result.getStrategy());
        log.info("Source Row Count: " + result.getSourceRowCount());
        log.info("Target Row Count: " + result.getTargetRowCount());

        if (isDryRun) {
            log.info("Dry run completed successfully!");
            log.info("Database connections validated and table counts retrieved.");
            log.info("Run without --dry-run to perform actual diff operation.");
        } else {
            log.info("Differences Found:");
            log.info("  Source missing rows: " + result.getSourceMissingCount());
            log.info("  Target missing rows: " + result.getTargetMissingCount());
            log.info("  Mismatched rows: " + result.getMismatchCount());
            log.info("  Total differences: " + result.getTotalDifferences());

            if (result.getTotalDifferences() == 0) {
                log.info("No differences found! The tables are identical.");
            } else {
                log.info("Differences detected. Check the configured result sinks for details.");
            }

            if (result.getDurationMs() != null) {
                log.info("Operation duration: " + result.getDurationMs() + " ms");
            }
        }
    }

    private String resourceDisplay(com.consilens.cli.model.ConnectionConfig connectionConfig) {
        if (connectionConfig == null || connectionConfig.getResource() == null) {
            return "(not set)";
        }
        com.consilens.cli.model.ConnectionConfig.ResourceConfig resource = connectionConfig.getResource();
        String location = "sql".equalsIgnoreCase(resource.getType())
                ? resource.getPath()
                : resource.getName();
        return resource.getType() + ":" + location;
    }
}

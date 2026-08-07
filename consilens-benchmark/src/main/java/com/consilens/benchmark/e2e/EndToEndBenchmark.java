package com.consilens.benchmark.e2e;

import com.consilens.benchmark.e2e.ScenarioLoader.Scenario;
import com.consilens.benchmark.report.BenchmarkResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 端到端基准：对每个选定场景拉起 CLI 子进程跑 examples yaml，产出 {@link BenchmarkResult}。
 *
 * <p>CLI jar 缺失、yaml 缺失或缺少数据库凭据时场景标记 SKIPPED（不判失败）；
 * 只有进程真实运行失败（非零退出、超时）才判 FAILED。
 */
public final class EndToEndBenchmark {

    public static final Duration DEFAULT_TIMEOUT = CliProcessRunner.DEFAULT_TIMEOUT;

    private static final String DEFAULT_CLI_JAR = "consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar";

    private final Path cliJar;
    private final List<String> scenarioIds;
    private final ScenarioLoader loader;
    private final CliProcessRunner runner;

    /**
     * @param scenarioIds 要执行的场景编号；空集合表示执行全部默认场景
     */
    public EndToEndBenchmark(List<String> scenarioIds) {
        this(defaultCliJar(), DEFAULT_TIMEOUT, scenarioIds);
    }

    public EndToEndBenchmark(Path cliJar, Duration timeout, List<String> scenarioIds) {
        this.cliJar = cliJar;
        this.scenarioIds = scenarioIds == null ? Collections.emptyList() : new ArrayList<>(scenarioIds);
        this.loader = new ScenarioLoader();
        this.runner = new CliProcessRunner(timeout);
    }

    /**
     * CLI fat jar 路径：环境变量 {@code CONSILENS_CLI_JAR} 覆盖，默认相对当前工作目录。
     */
    public static Path defaultCliJar() {
        String override = System.getenv("CONSILENS_CLI_JAR");
        return Paths.get(override == null || override.trim().isEmpty() ? DEFAULT_CLI_JAR : override.trim());
    }

    public List<BenchmarkResult> run() {
        List<String> ids = scenarioIds.isEmpty() ? loader.defaultScenarioIds() : scenarioIds;
        List<BenchmarkResult> results = new ArrayList<>();
        for (String id : ids) {
            results.add(runScenario(id));
        }
        return results;
    }

    private BenchmarkResult runScenario(String id) {
        Scenario scenario = loader.scenario(id);
        if (scenario == null) {
            return skipped(id, "unknown scenario id: " + id);
        }
        if (!Files.exists(cliJar)) {
            return skipped(id, "CLI jar not found: " + cliJar
                    + "; build it with ./mvnw -pl consilens-cli -am package");
        }
        if (!Files.exists(scenario.configPath())) {
            return skipped(id, "config file not found: " + scenario.configPath());
        }
        List<String> missing = missingEnv(scenario.requiredEnvVars());
        if (!missing.isEmpty()) {
            return skipped(id, "missing database credentials: " + String.join(", ", missing));
        }

        CliProcessRunner.RunResult run = runner.run(cliJar, scenario.configPath());
        if (run.isTimedOut()) {
            return failed(id, "timed out after " + DEFAULT_TIMEOUT.toMillis() + " ms");
        }
        if (run.getExitCode() != 0) {
            return failed(id, "CLI exited with code " + run.getExitCode() + ": " + tail(run.getOutput(), 20));
        }

        long durationMs = run.effectiveDurationMs();
        Map<String, Double> subMetrics = new LinkedHashMap<>();
        subMetrics.put("durationMs", (double) durationMs);
        if (run.getSourceRowCount() != null) {
            subMetrics.put("sourceRowCount", (double) run.getSourceRowCount());
            if (durationMs > 0) {
                subMetrics.put("rowsPerSecond", run.getSourceRowCount() * 1000.0 / durationMs);
            }
        }
        subMetrics.putAll(run.getDiffCounts());

        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioId(id);
        result.setScore((double) durationMs);
        result.setUnit("ms");
        result.setStatus("RUN");
        result.setDurationMs(durationMs);
        result.setTimestamp(Instant.now().toString());
        result.setSubMetrics(subMetrics);
        return result;
    }

    private static List<String> missingEnv(List<String> names) {
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            String value = System.getenv(name);
            if (value == null || value.trim().isEmpty()) {
                missing.add(name);
            }
        }
        return missing;
    }

    private static BenchmarkResult skipped(String id, String message) {
        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioId(id);
        result.setScore(0);
        result.setUnit("ms");
        result.setStatus("SKIPPED");
        result.setTimestamp(Instant.now().toString());
        result.setMessage(message);
        return result;
    }

    private static BenchmarkResult failed(String id, String message) {
        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioId(id);
        result.setScore(0);
        result.setUnit("ms");
        result.setStatus("FAILED");
        result.setTimestamp(Instant.now().toString());
        result.setMessage(message);
        return result;
    }

    private static String tail(String text, int lines) {
        if (text == null) {
            return "";
        }
        String[] parts = text.trim().split("\\R");
        StringBuilder tail = new StringBuilder();
        int start = Math.max(0, parts.length - lines);
        for (int i = start; i < parts.length; i++) {
            tail.append(parts[i]).append('\n');
        }
        return tail.toString();
    }
}

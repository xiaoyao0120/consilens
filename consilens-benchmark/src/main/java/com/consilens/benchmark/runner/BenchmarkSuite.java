package com.consilens.benchmark.runner;

import com.consilens.benchmark.baseline.Baseline;
import com.consilens.benchmark.baseline.BaselineEntry;
import com.consilens.benchmark.baseline.BaselineStore;
import com.consilens.benchmark.baseline.DriftChecker;
import com.consilens.benchmark.baseline.DriftReport;
import com.consilens.benchmark.e2e.EndToEndBenchmark;
import com.consilens.benchmark.micro.LocalDiffEngineBenchmark;
import com.consilens.benchmark.report.BenchmarkReportGenerator;
import com.consilens.benchmark.report.BenchmarkResult;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基准套件编排：运行 micro/e2e、聚合结果、读基线、漂移判定、产报告、可选回写基线。
 *
 * <p>micro 路径用 JMH 编程式 {@link Runner} 跑 {@link LocalDiffEngineBenchmark}，
 * 把每个 {@link RunResult} 的主指标解析为 {@link BenchmarkResult}（scenarioId = 场景编号 + 规模后缀）。
 * e2e 路径本单元留 placeholder，返回空列表，由 UNIT-E2E 接入。
 */
public final class BenchmarkSuite {

    static final String M01 = "M01";
    static final String M02 = "M02";

    private final BenchmarkOptions options;

    public BenchmarkSuite(BenchmarkOptions options) {
        this.options = options;
    }

    /**
     * 运行选定模式，返回退出码：有回归非零，否则 0。
     */
    public int run() throws RunnerException {
        List<BenchmarkResult> results = new ArrayList<>();
        if (options.runMicro()) {
            results.addAll(runMicro());
        }
        if (options.runE2e()) {
            results.addAll(runE2e());
        }

        return aggregateAndReport(results);
    }

    /**
     * 编程式跑 JMH 微基准，解析 RunResult 到 BenchmarkResult 列表。
     */
    public List<BenchmarkResult> runMicro() throws RunnerException {
        Options jmhOpts = new OptionsBuilder()
                .include(LocalDiffEngineBenchmark.class.getName())
                .mode(org.openjdk.jmh.annotations.Mode.Throughput)
                .forks(options.forks())
                .warmupIterations(options.warmupIterations())
                .measurementIterations(options.measurementIterations())
                .shouldFailOnError(true)
                .build();

        Collection<RunResult> runResults = new Runner(jmhOpts).run();

        List<BenchmarkResult> results = new ArrayList<>();
        for (RunResult rr : runResults) {
            String benchmark = rr.getParams().getBenchmark();
            String primary = rr.getPrimaryResult().getLabel();
            double score = rr.getPrimaryResult().getScore();
            String unit = rr.getPrimaryResult().getScoreUnit();

            String scenarioId = resolveScenarioId(benchmark, rr.getParams().getParam("rows"));
            String status = score > 0 ? "RUN" : "FAILED";
            Map<String, Double> subMetrics = new TreeMap<>();
            long sampleCount = (long) rr.getPrimaryResult().getStatistics().getN();
            subMetrics.put("sampleCount", (double) sampleCount);

            BenchmarkResult result = new BenchmarkResult();
            result.setScenarioId(scenarioId + "." + normalizeMethod(primary, benchmark));
            result.setScore(score);
            result.setUnit(unit);
            result.setStatus(status);
            // JMH 编程式 RunResult 不直接暴露单次 wall-clock 耗时；保留 0，真实采样数记入 subMetrics
            result.setDurationMs(0L);
            result.setTimestamp(Instant.now().toString());
            result.setSubMetrics(subMetrics);
            results.add(result);
        }
        return results;
    }

    public List<BenchmarkResult> runE2e() {
        return new EndToEndBenchmark(new ArrayList<>(options.scenarios())).run();
    }

    /**
     * 聚合：读基线、漂移判定、产报告、可选回写基线。返回退出码。
     */
    int aggregateAndReport(List<BenchmarkResult> results) {
        Path baselinePath = options.baselinePath();
        Baseline baseline = null;
        if (Files.exists(baselinePath)) {
            try {
                baseline = BaselineStore.load(baselinePath);
            } catch (RuntimeException e) {
                System.err.println("benchmark: failed to load baseline: " + e.getMessage());
            }
        }

        DriftReport drift = new DriftChecker().check(results, baseline);

       Path outputDir = options.outputDir();
       try {
           Files.createDirectories(outputDir);
           new BenchmarkReportGenerator().generate(results, drift, outputDir);
        } catch (RuntimeException | IOException e) {
            System.err.println("benchmark: report generation failed: " + e.getMessage());
        }

        if (options.updateBaseline() && !results.isEmpty()) {
            writeBaseline(results, baselinePath);
        }

        boolean hasRegression = drift.hasRegression();
        System.out.println("benchmark: regression=" + hasRegression + ", results=" + results.size());
        return hasRegression ? 1 : 0;
    }

    private void writeBaseline(List<BenchmarkResult> results, Path baselinePath) {
        Map<String, BaselineEntry> entries = new TreeMap<>();
        for (BenchmarkResult r : results) {
            BaselineEntry entry = new BaselineEntry();
            entry.setScore(r.getScore());
            entry.setUnit(r.getUnit());
            entry.setThreshold(defaultThreshold(r.getScenarioId()));
            entries.put(r.getScenarioId(), entry);
        }
        Baseline baseline = new Baseline();
        baseline.setVersion(1);
        baseline.setCreatedAt(Instant.now().toString());
        baseline.setJdk(System.getProperty("java.version"));
        baseline.setEntries(entries);
       try {
           if (baselinePath.getParent() != null) {
               Files.createDirectories(baselinePath.getParent());
           }
           BaselineStore.store(baseline, baselinePath);
           System.out.println("benchmark: baseline written to " + baselinePath);
        } catch (RuntimeException | IOException e) {
            System.err.println("benchmark: baseline write failed: " + e.getMessage());
        }
    }

    private static double defaultThreshold(String scenarioId) {
        // 微基准收紧，e2e 放宽
        return scenarioId.startsWith("E") ? 0.25 : 0.15;
    }

    private static String resolveScenarioId(String benchmark, String rows) {
        if (benchmark.contains("m01NoDifference")) {
            return M01 + "." + rows;
        }
        if (benchmark.contains("m02FivePercentDifference")) {
            return M02 + "." + rows;
        }
        return benchmark;
    }

    private static String normalizeMethod(String primary, String benchmark) {
        // JMH primary label 通常为方法名；用 benchmark 中的方法段更稳定
        String method = benchmark;
        int dot = benchmark.lastIndexOf('.');
        if (dot >= 0) {
            method = benchmark.substring(dot + 1);
        }
        return method;
    }
}

package com.consilens.benchmark.runner;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基准套件命令行参数。解析 --mode / --scenario / --update-baseline / --output-dir / --baseline-path
 * 以及可选的 JMH 精简参数 --forks / --warmup-iterations / --measurement-iterations。
 *
 * 默认 JMH 参数为精简值（1/1/1），便于本地快速验证；正式基准可在命令行覆盖。
 */
public final class BenchmarkOptions {

    public static final String MODE_MICRO = "micro";
    public static final String MODE_E2E = "e2e";
    public static final String MODE_ALL = "all";

    private static final String DEFAULT_OUTPUT_DIR = "target/benchmark/report";
    private static final String DEFAULT_BASELINE_PATH =
            "src/main/resources/baseline/benchmark-baseline.json";

    private final String mode;
    private final Set<String> scenarios;
    private final boolean updateBaseline;
    private final Path outputDir;
    private final Path baselinePath;
    private final int forks;
    private final int warmupIterations;
    private final int measurementIterations;

    private BenchmarkOptions(String mode, Set<String> scenarios, boolean updateBaseline,
                             Path outputDir, Path baselinePath,
                             int forks, int warmupIterations, int measurementIterations) {
        this.mode = mode;
        this.scenarios = Collections.unmodifiableSet(scenarios);
        this.updateBaseline = updateBaseline;
        this.outputDir = outputDir;
        this.baselinePath = baselinePath;
        this.forks = forks;
        this.warmupIterations = warmupIterations;
        this.measurementIterations = measurementIterations;
    }

    public String mode() {
        return mode;
    }

    public Set<String> scenarios() {
        return scenarios;
    }

    public boolean updateBaseline() {
        return updateBaseline;
    }

    public Path outputDir() {
        return outputDir;
    }

    public Path baselinePath() {
        return baselinePath;
    }

    public int forks() {
        return forks;
    }

    public int warmupIterations() {
        return warmupIterations;
    }

    public int measurementIterations() {
        return measurementIterations;
    }

    public boolean runMicro() {
        return MODE_MICRO.equals(mode) || MODE_ALL.equals(mode);
    }

    public boolean runE2e() {
        return MODE_E2E.equals(mode) || MODE_ALL.equals(mode);
    }

    /**
     * 解析命令行。未知参数忽略并记录到 stderr，不抛异常以保持套件可运行。
     */
    public static BenchmarkOptions parse(String[] args) {
        String mode = MODE_MICRO;
        Set<String> scenarios = new LinkedHashSet<>();
        boolean updateBaseline = false;
        Path outputDir = Paths.get(DEFAULT_OUTPUT_DIR);
        Path baselinePath = Paths.get(DEFAULT_BASELINE_PATH);
        int forks = 1;
        int warmupIterations = 1;
        int measurementIterations = 1;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--mode":
                    mode = next(args, ++i, arg);
                    break;
                case "--scenario":
                    String raw = next(args, ++i, arg);
                    for (String s : raw.split(",")) {
                        String trimmed = s.trim();
                        if (!trimmed.isEmpty()) {
                            scenarios.add(trimmed);
                        }
                    }
                    break;
                case "--update-baseline":
                    updateBaseline = true;
                    break;
                case "--output-dir":
                    outputDir = Paths.get(next(args, ++i, arg));
                    break;
                case "--baseline-path":
                    baselinePath = Paths.get(next(args, ++i, arg));
                    break;
                case "--forks":
                    forks = Integer.parseInt(next(args, ++i, arg));
                    break;
                case "--warmup-iterations":
                    warmupIterations = Integer.parseInt(next(args, ++i, arg));
                    break;
                case "--measurement-iterations":
                    measurementIterations = Integer.parseInt(next(args, ++i, arg));
                    break;
                default:
                    System.err.println("benchmark: ignoring unknown argument: " + arg);
            }
        }
        return new BenchmarkOptions(mode, scenarios, updateBaseline, outputDir, baselinePath,
                forks, warmupIterations, measurementIterations);
    }

    private static String next(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("benchmark: missing value for " + flag);
        }
        return args[index];
    }

    /**
     * 过滤场景：若未指定 --scenario 返回全集；否则返回交集，保持入参顺序。
     */
    public List<String> filterScenarios(List<String> candidates) {
        if (scenarios.isEmpty()) {
            return new ArrayList<>(candidates);
        }
        List<String> result = new ArrayList<>();
        for (String c : candidates) {
            if (scenarios.contains(c)) {
                result.add(c);
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return "BenchmarkOptions{mode=" + mode + ", scenarios=" + scenarios
                + ", updateBaseline=" + updateBaseline + ", outputDir=" + outputDir
                + ", baselinePath=" + baselinePath + ", forks=" + forks
                + ", warmup=" + warmupIterations + ", measurement=" + measurementIterations + "}";
    }
}

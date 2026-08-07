package com.consilens.benchmark.micro;

import com.consilens.core.algorithm.LocalDiffEngine;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * LocalDiffEngine 微基准：覆盖 M01（无差异）与 M02（5% 差异）两类场景，规模三档。
 *
 * <p>被测对象为 {@link LocalDiffEngine#findDifferences}，纯内存调用，不触数据库。输入行集
 * 由 {@link BenchmarkFixtures} 以固定种子确定性构造，与单元测试共用同一份 fixture 逻辑。
 *
 * <p>{@code @Setup} 将 LocalDiffEngine 的 log4j2 logger 调至 WARN，消除每次调用 log.info
 * 的噪声；{@code @TearDown} 还原原级别。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 1)
@Fork(1)
public class LocalDiffEngineBenchmark {

    private static final String TARGET_LOGGER = LocalDiffEngine.class.getName();
    private static final String KEY_COL = "id";
    private static final String EXTRA_COL = "payload";
    private static final long SEED = 42L;
    private static final double FIVE_PERCENT = 0.05;

    @Param({"100000", "500000", "1000000"})
    private int rows;

    private List<Object[]> leftRows;
    private List<Object[]> rightRows;
    private List<Object[]> rightRowsDiff;
    private List<String> keyCols;
    private List<String> extraCols;

    private LoggerContext loggerContext;
    private Level originalLevel;

    @Setup
    public void setup() {
        silenceLogger();

        final int keyColumns = 1;
        final int extraColumns = 1;
        leftRows = BenchmarkFixtures.buildRows(rows, SEED, keyColumns, extraColumns);
        // M01：相同种子产出相同行集，两侧完全一致
        rightRows = BenchmarkFixtures.buildRows(rows, SEED, keyColumns, extraColumns);
        // M02：右侧按 5% 比例修改 extra 列，主键保持不变
        rightRowsDiff = BenchmarkFixtures.withDifferences(leftRows, FIVE_PERCENT, SEED + 1);

        keyCols = Arrays.asList(KEY_COL);
        extraCols = Arrays.asList(EXTRA_COL);
    }

    /** M01：无差异，两侧行集完全一致。 */
    @Benchmark
    public void m01NoDifference() {
        LocalDiffEngine.findDifferences(leftRows, rightRows, keyCols, extraCols, keyCols, extraCols);
    }

    /** M02：5% 行存在差异。 */
    @Benchmark
    public void m02FivePercentDifference() {
        LocalDiffEngine.findDifferences(leftRows, rightRowsDiff, keyCols, extraCols, keyCols, extraCols);
    }

    private void silenceLogger() {
        loggerContext = (LoggerContext) LogManager.getContext(false);
        Configuration config = loggerContext.getConfiguration();
        LoggerConfig loggerConfig = config.getLoggerConfig(TARGET_LOGGER);
        originalLevel = loggerConfig.getLevel();
        LoggerConfig newConfig = new LoggerConfig(TARGET_LOGGER, Level.WARN, false);
        config.addLogger(TARGET_LOGGER, newConfig);
        loggerContext.updateLoggers();
    }

    @TearDown
    public void tearDown() {
        if (loggerContext != null) {
            Configuration config = loggerContext.getConfiguration();
            if (originalLevel != null) {
                LoggerConfig restored = new LoggerConfig(TARGET_LOGGER, originalLevel, false);
                config.addLogger(TARGET_LOGGER, restored);
            } else {
                config.removeLogger(TARGET_LOGGER);
            }
            loggerContext.updateLoggers();
        }
    }
}

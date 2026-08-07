package com.consilens.benchmark.report;

import com.consilens.benchmark.baseline.DriftReport;
import com.consilens.benchmark.baseline.DriftReport.DriftItem;
import com.consilens.benchmark.baseline.DriftReport.DriftStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 聚合 BenchmarkResult 与 DriftReport，产出 Markdown + results.json 报告。
 * 风格参考 {@code PerformanceReportGenerator} 的中文 Markdown 摘要+表格布局。
 */
@Slf4j
public class BenchmarkReportGenerator {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper mapper;

    public BenchmarkReportGenerator() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 生成 report.md 与 results.json 到给定目录。
     *
     * @param results 基准结果列表
     * @param drift   漂移报告
     * @param outputDir 输出目录
     */
    public void generate(List<BenchmarkResult> results, DriftReport drift, Path outputDir) {
        try {
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve("report.md"), buildMarkdown(results, drift));
            mapper.writeValue(outputDir.resolve("results.json").toFile(), buildJson(results, drift));
            log.info("benchmark report generated: {}", outputDir);
        } catch (IOException e) {
            throw new RuntimeException("failed to generate benchmark report", e);
        }
    }

    private String buildMarkdown(List<BenchmarkResult> results, DriftReport drift) {
        StringBuilder md = new StringBuilder();
        md.append("# Consilens 基准测试报告\n\n");
        md.append(String.format("**生成时间**: %s\n\n", LocalDateTime.now().format(DATE_FORMATTER)));
        md.append(String.format("**场景数**: %d  |  **回归**: %s\n\n",
                results.size(), drift.hasRegression() ? "是 ❌" : "否 ✅"));

        md.append("## 场景结果\n\n");
        md.append("| 场景 | 状态 | 主指标 | 单位 | 耗时(ms) | 说明 |\n");
        md.append("|------|------|-------:|------|--------:|------|\n");
        for (BenchmarkResult r : results) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %.4f | %s | %d | %s |\n",
                    nullSafe(r.getScenarioId()),
                    r.getStatus(),
                    r.getScore(),
                    nullSafe(r.getUnit()),
                    r.getDurationMs(),
                    nullSafe(r.getMessage())));
        }

        md.append("\n## 基线漂移\n\n");
        md.append("| 场景 | 判定 | 当前 | 基线 | 比值 | 说明 |\n");
        md.append("|------|------|-----:|-----:|-----:|------|\n");
        for (DriftItem item : drift.getItems()) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %.4f | %.4f | %s | %s |\n",
                    nullSafe(item.getKey()),
                    item.getStatus(),
                    item.getCurrent(),
                    item.getBaseline(),
                    Double.isNaN(item.getRatio()) ? "N/A" : String.format(Locale.ROOT, "%.4f", item.getRatio()),
                    nullSafe(item.getMessage())));
        }

        int pass = count(drift, DriftStatus.PASS);
        int warn = count(drift, DriftStatus.WARN);
        int fail = count(drift, DriftStatus.FAIL);
        int news = count(drift, DriftStatus.NEW);
        int skipped = count(drift, DriftStatus.SKIPPED);
        md.append("\n## 汇总\n\n");
        md.append(String.format("- PASS: %d  |  WARN: %d  |  FAIL: %d  |  NEW: %d  |  SKIPPED: %d\n",
                pass, warn, fail, news, skipped));
        if (drift.hasRegression()) {
            md.append("\n> ⚠ 存在性能回归(FAIL)，请检查上方漂移明细。\n");
        }
        return md.toString();
    }

    private ObjectNode buildJson(List<BenchmarkResult> results, DriftReport drift) {
        ObjectNode root = mapper.createObjectNode();
        root.put("generatedAt", LocalDateTime.now().format(DATE_FORMATTER));
        root.put("hasRegression", drift.hasRegression());
        ArrayNode arr = root.putArray("results");
        for (BenchmarkResult r : results) {
            ObjectNode node = arr.addObject();
            node.put("scenarioId", nullSafe(r.getScenarioId()));
            node.put("status", String.valueOf(r.getStatus()));
            node.put("score", r.getScore());
            node.put("unit", nullSafe(r.getUnit()));
            node.put("durationMs", r.getDurationMs());
            node.put("timestamp", nullSafe(r.getTimestamp()));
            node.put("message", nullSafe(r.getMessage()));
            if (r.getSubMetrics() != null && !r.getSubMetrics().isEmpty()) {
                ObjectNode sub = node.putObject("subMetrics");
                r.getSubMetrics().forEach(sub::put);
            }
        }
        ArrayNode driftArr = root.putArray("drift");
        for (DriftItem item : drift.getItems()) {
            ObjectNode node = driftArr.addObject();
            node.put("key", nullSafe(item.getKey()));
            node.put("status", String.valueOf(item.getStatus()));
            node.put("current", item.getCurrent());
            node.put("baseline", item.getBaseline());
            if (!Double.isNaN(item.getRatio())) {
                node.put("ratio", item.getRatio());
            }
            node.put("message", nullSafe(item.getMessage()));
        }
        return root;
    }

    private int count(DriftReport drift, DriftStatus status) {
        int c = 0;
        for (DriftItem item : drift.getItems()) {
            if (item.getStatus() == status) {
                c++;
            }
        }
        return c;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}

package com.consilens.cli.config;

import com.consilens.cli.model.CliConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 示例配置覆盖矩阵：确保 examples 下的配置能覆盖配置模型的所有字段、
 * 所有枚举取值与所有字段类型，并保证种子数据刻意差异的完整性。
 */
class ExampleCoverageMatrixTest {

    private static final List<String> EXPECTED_PAIRS = List.of(
            "mysql-pg", "mysql-clickhouse", "mysql-tidb", "mysql-starrocks",
            "mysql-doris", "mysql-trino", "mysql-presto", "mysql-oceanbase",
            "mysql-oracle", "mysql-sqlserver");

    private static final List<String> EXPECTED_SCENARIOS = List.of(
            "01-custom-sql-checksum.yaml",
            "02-detail-to-aggregate.yaml",
            "03-large-table.yaml",
            "04-mapped-checksum.yaml",
            "05-same-db-join.yaml",
            "06-performance-full-fields.yaml",
            "07-exclude-fields.yaml",
            "09-json-format.json",
            "11-identical.yaml");

    private static final List<String> EXPECTED_SAME_DB = List.of(
            "mysql", "postgresql", "clickhouse", "tidb", "starrocks", "doris",
            "trino", "presto", "oceanbase", "oracle", "sqlserver");

    private static final List<String> SAME_DB_SCENARIOS = List.of(
            "01-identical.yaml",
            "02-join-diff.yaml");

    private static Path examplesDirectory() {
        try {
            Path testClasses = Paths.get(ExampleCoverageMatrixTest.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            Path examples = testClasses.getParent().getParent().getParent().resolve("examples").normalize();
            if (!Files.isDirectory(examples)) {
                throw new IllegalStateException("examples directory not found at: " + examples);
            }
            return examples;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate examples directory from test classes location", e);
        }
    }

    private static List<Path> allExampleConfigs() throws IOException {
        try (Stream<Path> stream = Files.walk(examplesDirectory())) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
                    })
                    .filter(path -> !path.getFileName().toString().equals("test-baselines.json"))
                    .filter(path -> !path.toString().endsWith(".bak"))
                    .filter(path -> !path.toString().contains("_archive"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static List<JsonNode> parseAllConfigs() {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            return allExampleConfigs().stream()
                    .map(path -> {
                        try {
                            return mapper.readTree(path.toFile());
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allExampleConfigs")
    void shouldLoadEveryExampleConfiguration(Path configPath) throws Exception {
        ConfigurationManager configurationManager = new ConfigurationManager(testEnvironment());
        CliConfiguration config = configurationManager.loadConfiguration(configPath.toString(), false);

        assertNotNull(config, "Config should load: " + configPath);
        assertNotNull(config.getSource(), "Source config should not be null: " + configPath);
        assertNotNull(config.getTarget(), "Target config should not be null: " + configPath);
        assertNotNull(config.getComparison(), "Comparison config should not be null: " + configPath);
        assertNotNull(config.getComparison().getKeys(), "comparison.keys should not be null: " + configPath);
        config.validate();
    }

    @Test
    void shouldCoverAllConfigurationFieldsAcrossExamples() {
        List<JsonNode> configs = parseAllConfigs();
        List<String> requiredPaths = List.of(
                "source.type", "source.name", "source.connection.url",
                "source.connection.username", "source.connection.password",
                "source.resource.type", "source.resource.name", "source.resource.path",
                "source.readOptions",
                "target.type", "target.connection.url", "target.resource.type",
                "comparison.keys.source", "comparison.keys.target",
                "comparison.fields.source", "comparison.fields.target",
                "comparison.exclude.source", "comparison.exclude.target",
                "comparison.mappings", "comparison.extraColumns",
                "comparison.filters.source", "comparison.filters.target",
                "strategy.mode", "strategy.algorithm",
                "strategy.bisectionFactor", "strategy.bisectionThreshold",
                "strategy.batchSize", "strategy.enableProfiling",
                "strategy.localCompare.mode", "strategy.maxDifferences",
                "concurrency.io.core", "concurrency.io.max",
                "concurrency.io.queueSize", "concurrency.io.keepAliveSeconds",
                "concurrency.io.threadNamePrefix",
                "concurrency.cpu.core", "concurrency.cpu.max",
                "concurrency.cpu.queueSize", "concurrency.cpu.keepAliveSeconds",
                "concurrency.cpu.threadNamePrefix",
                "normalization.global", "normalization.source", "normalization.target",
                "result.failOnSinkError", "result.sinks");

        for (String requiredPath : requiredPaths) {
            assertTrue(configs.stream().anyMatch(node -> hasPath(node, requiredPath)),
                    "examples 目录缺少配置字段覆盖: " + requiredPath);
        }
    }

    @Test
    void shouldCoverAllEnumValuesAcrossExamples() {
        List<JsonNode> configs = parseAllConfigs();
        assertTrue(hasValue(configs, "strategy.mode", "checksum"), "缺少 strategy.mode=checksum");
        assertTrue(hasValue(configs, "strategy.mode", "join"), "缺少 strategy.mode=join");
        assertTrue(hasValue(configs, "strategy.algorithm", "concat"), "缺少 strategy.algorithm=concat");
        assertTrue(hasValue(configs, "strategy.algorithm", "xor"), "缺少 strategy.algorithm=xor");
        assertTrue(hasValue(configs, "strategy.localCompare.mode", "full"), "缺少 localCompare.mode=full");
        assertTrue(hasValue(configs, "strategy.localCompare.mode", "row-hash"), "缺少 localCompare.mode=row-hash");
        assertTrue(hasValue(configs, "source.resource.type", "table"), "缺少 resource.type=table");
        assertTrue(hasValue(configs, "source.resource.type", "sql"), "缺少 resource.type=sql");

        Set<String> sinkFormats = new HashSet<>();
        Set<String> sinkTypes = new HashSet<>();
        Set<Boolean> sinkEnabled = new HashSet<>();
        for (JsonNode config : configs) {
            JsonNode sinks = config.at("/result/sinks");
            if (sinks == null || !sinks.isArray()) {
                continue;
            }
            for (JsonNode sink : sinks) {
                if (sink.hasNonNull("format")) {
                    sinkFormats.add(sink.get("format").asText());
                }
                if (sink.hasNonNull("type")) {
                    sinkTypes.add(sink.get("type").asText());
                }
                if (sink.has("enabled")) {
                    sinkEnabled.add(sink.get("enabled").asBoolean());
                }
            }
        }
        assertTrue(sinkFormats.containsAll(List.of("console", "csv", "json", "table")),
                "缺少 sink format 覆盖: " + sinkFormats);
        assertTrue(sinkTypes.containsAll(List.of("result", "diff-record")),
                "缺少 sink type 覆盖: " + sinkTypes);
        assertTrue(sinkEnabled.containsAll(List.of(true, false)),
                "缺少 sink enabled true/false 覆盖: " + sinkEnabled);
    }

    @Test
    void shouldCoverAllNormalizationRuleParameters() {
        List<JsonNode> configs = parseAllConfigs();
        List<String> requiredTokens = List.of(
                "precision", "rounding", "format", "timezone", "comparisonMode",
                "encoding", "uppercase", "trueValue", "falseValue", "nullValue");
        for (String token : requiredTokens) {
            boolean covered = configs.stream()
                    .filter(node -> node.has("normalization"))
                    .map(node -> node.get("normalization"))
                    .anyMatch(node -> anyFieldContainsKey(node, token));
            assertTrue(covered, "normalization 缺少规则参数覆盖: " + token);
        }
    }

    @Test
    void shouldCoverAllJsonValueTypesAcrossExamples() {
        List<JsonNode> configs = parseAllConfigs();
        assertTrue(configs.stream().anyMatch(node -> containsType(node, JsonNode::isTextual)), "缺少 string 类型");
        assertTrue(configs.stream().anyMatch(node -> containsType(node, JsonNode::isInt)), "缺少 int 类型");
        assertTrue(configs.stream().anyMatch(node -> containsType(node, JsonNode::isLong)), "缺少 long 类型");
        assertTrue(configs.stream().anyMatch(node -> containsType(node, JsonNode::isBoolean)), "缺少 boolean 类型");
        assertTrue(configs.stream().anyMatch(node -> containsType(node, JsonNode::isArray)), "缺少 array 类型");
        assertTrue(configs.stream().anyMatch(node -> containsType(node, JsonNode::isObject)), "缺少 object 类型");
    }

    @Test
    void shouldHaveUniformScenarioSetPerPair() throws IOException {
        for (String pair : EXPECTED_PAIRS) {
            Path pairDir = examplesDirectory().resolve("cross-db").resolve(pair);
            assertTrue(Files.isDirectory(pairDir), "缺少 pair 目录: " + pair);
            for (String scenario : EXPECTED_SCENARIOS) {
                assertTrue(Files.exists(pairDir.resolve(scenario)), pair + " 缺少场景配置: " + scenario);
            }
            assertTrue(Files.exists(pairDir.resolve("load-mysql.sql")), pair + " 缺少 load-mysql.sql");
            assertTrue(targetLoadSql(pairDir) != null, pair + " 缺少目标端 load 脚本");
        }
        assertTrue(Files.exists(examplesDirectory().resolve("cross-db/mysql-pg/08-output-postgres.yaml")),
                "cross-db/mysql-pg 缺少 08-output-postgres.yaml（输出方言覆盖）");
        assertTrue(Files.exists(examplesDirectory().resolve("cross-db/mysql-doris/10-partition-filter.yaml")),
                "cross-db/mysql-doris 缺少 10-partition-filter.yaml（分区过滤覆盖）");
        for (String name : EXPECTED_SAME_DB) {
            Path sameDbDir = examplesDirectory().resolve("same-db").resolve(name);
            assertTrue(Files.isDirectory(sameDbDir), "same-db 缺少数据源目录: " + name);
            for (String scenario : SAME_DB_SCENARIOS) {
                assertTrue(Files.exists(sameDbDir.resolve(scenario)),
                        "same-db/" + name + " 缺少场景配置: " + scenario);
            }
            try (Stream<Path> stream = Files.list(sameDbDir)) {
                long loadSqlCount = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith("load-"))
                        .count();
                assertTrue(loadSqlCount > 0, "same-db/" + name + " 缺少 load 脚本");
            }
        }
        assertTrue(Files.exists(examplesDirectory().resolve("same-db/mysql/03-normalization.yaml")),
                "same-db/mysql 缺少 03-normalization.yaml（normalization 全参数覆盖）");
    }

    @Test
    void shouldKeepSeedDataDifferencesConsistent() throws IOException {
        for (String pair : EXPECTED_PAIRS) {
            Path targetSql = targetLoadSql(examplesDirectory().resolve("cross-db").resolve(pair));
            String text = Files.readString(targetSql);
            assertTrue(text.contains("99999.9999"), pair + " 缺少 amount MISMATCH (REC0000000001)");
            assertTrue(text.contains("modified_status"), pair + " 缺少 status MISMATCH (REC0000000002)");
            assertTrue(text.contains("REC_EXTRA_001"), pair + " 缺少 SOURCE_MISSING (REC_EXTRA_001)");
            assertTrue(text.contains("n != 5") || text.contains("n <> 5") || text.contains("LEVEL <> 5")
                            || text.contains("n%5") || text.contains("n % 5"),
                    pair + " 缺少 TARGET_MISSING (跳过 n=5)");
        }
    }

    @Test
    void shouldKeepMappedAndJoinScenariosBackedBySeedDifferences() throws IOException {
        for (String pair : EXPECTED_PAIRS) {
            Path targetSql = targetLoadSql(examplesDirectory().resolve("cross-db").resolve(pair));
            String text = Files.readString(targetSql);
            assertTrue(text.contains("modified@example.com"),
                    pair + " 的 04-mapped-checksum 需要 users email MISMATCH");
            assertTrue(text.contains("orders_backup") && text.contains("99999.9999"),
                    pair + " 的 05-same-db-join 需要 orders_backup amount MISMATCH");
            assertTrue(text.contains("10001"),
                    pair + " 的 05-same-db-join 需要 orders 额外订单 (SOURCE_MISSING)");
        }
    }

    @Test
    void shouldKeepBaselineFileInSyncWithExamples() throws Exception {
        Path baselineFile = examplesDirectory().resolve("test-baselines.json");
        assertTrue(Files.exists(baselineFile), "缺少 test-baselines.json");
        JsonNode baselines = new ObjectMapper().readTree(baselineFile.toFile()).get("baselines");

        Set<String> expectedKeys = new HashSet<>();
        for (String pair : EXPECTED_PAIRS) {
            for (String scenario : EXPECTED_SCENARIOS) {
                expectedKeys.add("cross-db/" + pair + "/" + scenario);
            }
        }
        expectedKeys.add("cross-db/mysql-pg/08-output-postgres.yaml");
        expectedKeys.add("cross-db/mysql-doris/10-partition-filter.yaml");
        for (String name : EXPECTED_SAME_DB) {
            for (String scenario : SAME_DB_SCENARIOS) {
                expectedKeys.add("same-db/" + name + "/" + scenario);
            }
        }
        expectedKeys.add("same-db/mysql/03-normalization.yaml");
        Set<String> actualKeys = new HashSet<>();
        baselines.fieldNames().forEachRemaining(actualKeys::add);

        Set<String> missing = new HashSet<>(expectedKeys);
        missing.removeAll(actualKeys);
        Set<String> extra = new HashSet<>(actualKeys);
        extra.removeAll(expectedKeys);
        assertTrue(missing.isEmpty(), "test-baselines.json 缺少条目: " + missing);
        assertTrue(extra.isEmpty(), "test-baselines.json 存在多余条目: " + extra);
    }

    private static Path targetLoadSql(Path pairDir) throws IOException {
        try (Stream<Path> stream = Files.list(pairDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("load-"))
                    .filter(path -> !path.getFileName().toString().equals("load-mysql.sql"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static boolean hasPath(JsonNode node, String path) {
        JsonNode current = node;
        for (String segment : path.split("\\.")) {
            current = current.get(segment);
            if (current == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasValue(List<JsonNode> configs, String path, String value) {
        for (JsonNode config : configs) {
            JsonNode node = config;
            boolean found = true;
            for (String segment : path.split("\\.")) {
                node = node.get(segment);
                if (node == null) {
                    found = false;
                    break;
                }
            }
            if (found && node.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyFieldContainsKey(JsonNode node, String key) {
        if (node == null || !node.isObject()) {
            return false;
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getKey().equals(key) || anyFieldContainsKey(entry.getValue(), key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsType(JsonNode node, Predicate<JsonNode> predicate) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (predicate.test(node)) {
            return true;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsType(child, predicate)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                if (containsType(fields.next().getValue(), predicate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, String> testEnvironment() {
        Map<String, String> env = new HashMap<>();
        for (String name : List.of("MYSQL", "PG", "CLICKHOUSE", "TIDB", "STARROCKS",
                "DORIS", "ORACLE", "SQLSERVER", "TRINO", "PRESTO", "OCEANBASE")) {
            env.put(name + "_USER", "test_user");
            env.put(name + "_PASSWORD", "test_password");
        }
        return env;
    }
}

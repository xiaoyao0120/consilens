package com.consilens.benchmark.e2e;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * e2e 场景编号到 examples yaml 路径的映射。场景编号稳定，避免基线失效。
 *
 * <p>复用 examples 既有配置，不复制数据集；examples 目录可用环境变量
 * {@code CONSILENS_EXAMPLES_DIR} 覆盖，默认相对当前工作目录。
 */
public final class ScenarioLoader {

    public static final String E02 = "E02";
    public static final String E03 = "E03";
    public static final String JOIN = "join";
    public static final String GENERATED = "G01";
    public static final String GENERATED_POSTGRESQL = "G02";

    private static final List<String> DEFAULT_SCENARIOS =
            Collections.unmodifiableList(Arrays.asList(E02, E03, JOIN));

    private final Path examplesDir;

    public ScenarioLoader() {
        this(examplesDir());
    }

    public ScenarioLoader(Path examplesDir) {
        this.examplesDir = examplesDir;
    }

    public static Path examplesDir() {
        String override = System.getenv("CONSILENS_EXAMPLES_DIR");
        return Paths.get(override == null || override.trim().isEmpty() ? "examples" : override.trim());
    }

    public List<String> defaultScenarioIds() {
        return DEFAULT_SCENARIOS;
    }

    /**
     * 返回场景定义；未知场景编号返回 {@code null}。
     */
    public Scenario scenario(String id) {
        switch (id) {
            case E02:
                return new Scenario(E02, examplesDir.resolve("cross-db/mysql-pg/03-large-table.yaml"),
                        Arrays.asList("MYSQL_USER", "MYSQL_PASSWORD", "PG_USER", "PG_PASSWORD"));
            case E03:
                return new Scenario(E03, examplesDir.resolve("cross-db/mysql-pg/06-performance-full-fields.yaml"),
                        Arrays.asList("MYSQL_USER", "MYSQL_PASSWORD", "PG_USER", "PG_PASSWORD"));
            case JOIN:
                return new Scenario(JOIN, examplesDir.resolve("same-db/mysql/02-join-diff.yaml"),
                        Arrays.asList("MYSQL_USER", "MYSQL_PASSWORD"));
            case GENERATED:
                return new Scenario(GENERATED, generatedConfigPath(),
                        Arrays.asList("BENCHMARK_JDBC_URL", "BENCHMARK_DB_USER", "BENCHMARK_DB_PASSWORD"));
            case GENERATED_POSTGRESQL:
                return new Scenario(GENERATED_POSTGRESQL, generatedPostgresqlConfigPath(),
                        Arrays.asList("BENCHMARK_JDBC_URL", "BENCHMARK_DB_USER", "BENCHMARK_DB_PASSWORD"));
            default:
                return null;
        }
    }

    private Path generatedConfigPath() {
        String override = System.getenv("CONSILENS_GENERATED_CONFIG");
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override.trim());
        }
        return Paths.get("consilens-benchmark/src/main/resources/e2e/generated-mysql.yaml");
    }

    private Path generatedPostgresqlConfigPath() {
        String override = System.getenv("CONSILENS_GENERATED_POSTGRESQL_CONFIG");
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override.trim());
        }
        return Paths.get("consilens-benchmark/src/main/resources/e2e/generated-postgresql.yaml");
    }

    /**
     * 单个 e2e 场景：编号、yaml 路径、运行所需的数据库凭据环境变量。
     */
    public static final class Scenario {

        private final String id;
        private final Path configPath;
        private final List<String> requiredEnvVars;

        public Scenario(String id, Path configPath, List<String> requiredEnvVars) {
            this.id = id;
            this.configPath = configPath;
            this.requiredEnvVars = Collections.unmodifiableList(requiredEnvVars);
        }

        public String id() {
            return id;
        }

        public Path configPath() {
            return configPath;
        }

        public List<String> requiredEnvVars() {
            return requiredEnvVars;
        }
    }
}

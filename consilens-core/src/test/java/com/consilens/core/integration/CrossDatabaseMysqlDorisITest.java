package com.consilens.core.integration;

import com.consilens.connector.api.model.TablePath;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.segment.TableSegment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-database (MySQL → Doris) Checksum diff integration test.
 * Doris uses MySQL protocol but has specific syntax for CREATE TABLE.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("跨数据库 MySQL vs Doris Checksum Diff 集成测试")
class CrossDatabaseMysqlDorisITest {

    private static final String DORIS_FE_IP = "172.30.0.2";
    private static final String DORIS_BE_IP = "172.30.0.3";

    private static final Network DORIS_NETWORK = Network.builder()
            .createNetworkCmdModifier(command -> command.withIpam(
                    new com.github.dockerjava.api.model.Network.Ipam()
                            .withConfig(new com.github.dockerjava.api.model.Network.Ipam.Config()
                                    .withSubnet("172.30.0.0/24"))))
            .build();

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("consilens_demo")
            .withUsername("test")
            .withPassword("test123")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Container
    private static final GenericContainer<?> DORIS_FE = new GenericContainer<>(
            DockerImageName.parse("apache/doris:fe-latest"))
            .withNetwork(DORIS_NETWORK)
            .withNetworkAliases("doris-fe")
            .withCreateContainerCmdModifier(command -> command.withIpv4Address(DORIS_FE_IP))
            .withEnv("FE_SERVERS", "fe1:" + DORIS_FE_IP + ":9010")
            .withEnv("FE_ID", "1")
            .withExposedPorts(9030, 8030)
            .waitingFor(new LogMessageWaitStrategy()
                    .withRegEx(".*Starting master FE node.*\\s")
                    .withStartupTimeout(Duration.ofMinutes(10)));

    @Container
    private static final GenericContainer<?> DORIS_BE = new GenericContainer<>(
            DockerImageName.parse("apache/doris:be-latest"))
            .withNetwork(DORIS_NETWORK)
            .withNetworkAliases("doris-be")
            .withCreateContainerCmdModifier(command -> command.withIpv4Address(DORIS_BE_IP))
            .withEnv("FE_SERVERS", "fe1:" + DORIS_FE_IP + ":9010")
            .withEnv("BE_ADDR", DORIS_BE_IP + ":9050")
            .dependsOn(DORIS_FE)
            .waitingFor(Wait.forLogMessage(".*BE node is ready.*\\s", 1)
                    .withStartupTimeout(Duration.ofMinutes(10)));

    private static DatabaseAdapter mysqlAdapter;
    private static DatabaseAdapter dorisAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        mysqlAdapter = CrossDatabaseITestBase.createAdapter("mysql-source", MYSQL, "mysql");

        String dorisHost = DORIS_FE.getHost();
        Integer dorisPort = DORIS_FE.getMappedPort(9030);

        // First create database on Doris (connect without database)
        String dorisRootUrl = "jdbc:mysql://" + dorisHost + ":" + dorisPort + "/?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai";
        DatabaseAdapter dorisRootAdapter = CrossDatabaseITestBase.createAdapter("doris-root", dorisRootUrl, "root", "", "doris");
        CrossDatabaseITestBase.executeSql(dorisRootAdapter, "CREATE DATABASE IF NOT EXISTS consilens_demo");
        dorisRootAdapter.close();

        // Now connect with database
        String dorisUrl = "jdbc:mysql://" + dorisHost + ":" + dorisPort + "/consilens_demo?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai";
        dorisAdapter = CrossDatabaseITestBase.createAdapter("doris-target", dorisUrl, "root", "", "doris");

        createTestTable(mysqlAdapter, "cross_source");
        createTestTable(dorisAdapter, "cross_target");

        insertTestData(mysqlAdapter, "cross_source");
        insertTestData(dorisAdapter, "cross_target");
    }

    @AfterAll
    static void tearDown() {
        if (mysqlAdapter != null) mysqlAdapter.close();
        if (dorisAdapter != null) dorisAdapter.close();
    }

    private static void createTestTable(DatabaseAdapter adapter, String tableName) throws Exception {
        String dialect = adapter.getConnectorType();
        if ("doris".equalsIgnoreCase(dialect)) {
            // Doris uses MySQL protocol but requires ENGINE=OLAP and DUPLICATE KEY
            CrossDatabaseITestBase.executeSql(adapter, "DROP TABLE IF EXISTS " + tableName);
            CrossDatabaseITestBase.executeSql(adapter,
                "CREATE TABLE " + tableName + " (" +
                "id INT, name VARCHAR(100), value DECIMAL(10,2), " +
                "status VARCHAR(20), created_at DATETIME, is_active BOOLEAN" +
                ") ENGINE=OLAP DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 " +
                "PROPERTIES (\"replication_num\" = \"1\")");
        } else {
            CrossDatabaseITestBase.executeSql(adapter, "DROP TABLE IF EXISTS " + tableName);
            CrossDatabaseITestBase.executeSql(adapter,
                "CREATE TABLE " + tableName + " (" +
                "id INT PRIMARY KEY, name VARCHAR(100), value DECIMAL(10,2), " +
                "status VARCHAR(20), created_at TIMESTAMP, is_active BOOLEAN)");
        }
    }

    private static void insertTestData(DatabaseAdapter adapter, String tableName) throws Exception {
        for (int i = 1; i <= 10; i++) {
            String sql = String.format(
                "INSERT INTO %s (id, name, value, status, created_at, is_active) VALUES (%d, 'item_%d', %s, 'active', '2026-01-01 00:00:00', %d)",
                tableName, i, i, String.valueOf(i * 10.50), i % 2);
            CrossDatabaseITestBase.executeSql(adapter, sql);
        }
    }

    @Test
    @Order(1)
    @DisplayName("MySQL 和 Doris 中相同数据应无差异")
    void identicalDataAcrossDatabasesShouldHaveNoDifferences() throws Exception {
        TableSegment seg1 = TableSegment.builder()
                .database(mysqlAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(dorisAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_target"))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        DiffResult result = CrossDatabaseITestBase.runChecksumDiff(seg1, seg2);

        assertThat(result).isNotNull();
        assertThat(result.hasDifferences()).isFalse();
        assertThat(result.getStatistics().getSourceRowCount()).isEqualTo(10);
        assertThat(result.getStatistics().getTargetRowCount()).isEqualTo(10);
    }

    @Test
    @Order(2)
    @DisplayName("MySQL vs Doris 应检测到数据差异")
    void shouldDetectDifferencesAcrossDatabases() throws Exception {
        CrossDatabaseITestBase.executeSql(dorisAdapter,
            "INSERT INTO cross_target (id, name, value, status, created_at, is_active) VALUES (11, 'extra_item', 99.99, 'active', '2026-01-01 00:00:00', 1)");

        TableSegment seg1 = TableSegment.builder()
                .database(mysqlAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(dorisAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_target"))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        DiffResult result = CrossDatabaseITestBase.runChecksumDiff(seg1, seg2);

        assertThat(result).isNotNull();
        assertThat(result.hasDifferences()).isTrue();

        List<DiffRow> sourceMissing = result.getDifferences().stream()
                .filter(r -> r.getOperation() == DiffOperation.SOURCE_MISSING)
                .collect(Collectors.toList());
        assertThat(sourceMissing).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("MySQL 与 Doris 应对所有公共类型和四类结果给出精确结论")
    void shouldVerifyPublicTypeFamiliesAndEveryDiffDirection() throws Exception {
        CrossDatabaseAccuracyFixture.verify(
                mysqlAdapter, TablePath.of("consilens_demo", "placeholder"),
                dorisAdapter, TablePath.of("consilens_demo", "placeholder"));
    }
}

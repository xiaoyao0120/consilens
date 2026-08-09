package com.consilens.cli.service;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.connector.api.MetadataQueryGenerator;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.spi.ConnectorProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 全 Connector 的快速契约门禁。它不连接数据库，而是保证插件发现、方言装配和核心 SQL
 * 生成入口在每次 Maven package 时全部可用；精确方言字符串继续由各插件测试负责。
 */
class ConnectorDialectContractTest {

    @Test
    void shouldKeepExamplesConnectorProvidersAndDialectsInSync() throws IOException {
        Set<String> expectedTypes = exampleConnectorTypes();
        Map<String, ConnectorProvider> connectorProviders = loadProviders(
                ConnectorProvider.class, ConnectorProvider::getType);
        Map<String, DatabaseDialectProvider> dialectProviders = loadProviders(
                DatabaseDialectProvider.class, DatabaseDialectProvider::getConnectorType);

        assertEquals(expectedTypes, connectorProviders.keySet(),
                "same-db 示例与 ConnectorProvider 清单不一致");
        assertEquals(expectedTypes, dialectProviders.keySet(),
                "same-db 示例与 DatabaseDialectProvider 清单不一致");

        for (String type : expectedTypes) {
            verifyDialectContract(type, dialectProviders.get(type));
        }
    }

    private void verifyDialectContract(String type, DatabaseDialectProvider provider) {
        DatabaseDialect dialect = provider.create();
        assertEquals(type, dialect.getConnectorType(), type + " 方言类型不一致");
        assertNotNull(dialect.getCapabilityProvider(), type + " 缺少 capability provider");
        assertNotNull(dialect.getTransactionManager(), type + " 缺少 transaction manager");
        assertNotNull(dialect.getConnectionPoolOptimizer(), type + " 缺少 pool optimizer");
        assertNotNull(dialect.getDataTypeHandler(), type + " 缺少 data type handler");

        SqlQueryGenerator sql = dialect.getSqlQueryGenerator();
        assertNotNull(sql, type + " 缺少 SQL generator");
        assertSql(type, "count", sql.getCountSQL("regression", "orders", "id > 0"));
        assertSql(type, "select", sql.getSelectSQL(
                "regression", "orders", List.of("id", "amount"), "id > 0", List.of("id")));

        MetadataQueryGenerator metadata = dialect.getMetadataQueryGenerator();
        assertNotNull(metadata, type + " 缺少 metadata generator");
        assertSql(type, "table exists", metadata.getTableExistsSQL("regression", "orders"));
        assertSql(type, "table columns", metadata.getTableColumnsSQL("regression", "orders"));
        assertSql(type, "health check", metadata.getHealthCheckSQL());

        assertSql(type, "timestamp normalization",
                dialect.getDataTypeHandler().normalizeColumn("created_at", DataType.TIMESTAMP));
        assertNotNull(dialect.getDataTypeHandler().convertToTypeDescriptor("VARCHAR(255)"),
                type + " 无法转换 VARCHAR 类型");
    }

    private void assertSql(String type, String capability, String sql) {
        assertNotNull(sql, type + " 的 " + capability + " SQL 为 null");
        assertFalse(sql.trim().isEmpty(), type + " 的 " + capability + " SQL 为空");
    }

    private Set<String> exampleConnectorTypes() throws IOException {
        Path sameDb = examplesDirectory().resolve("same-db");
        try (var paths = Files.list(sameDb)) {
            return paths.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    private Path examplesDirectory() {
        try {
            Path testClasses = Paths.get(ConnectorDialectContractTest.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            Path examples = testClasses.getParent().getParent().getParent().resolve("examples").normalize();
            if (!Files.isDirectory(examples)) {
                throw new IllegalStateException("examples directory not found at: " + examples);
            }
            return examples;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate examples directory", e);
        }
    }

    private <T> Map<String, T> loadProviders(Class<T> providerType, Function<T, String> typeExtractor) {
        return ServiceLoader.load(providerType).stream()
                .map(ServiceLoader.Provider::get)
                .collect(Collectors.toMap(typeExtractor, Function.identity()));
    }
}

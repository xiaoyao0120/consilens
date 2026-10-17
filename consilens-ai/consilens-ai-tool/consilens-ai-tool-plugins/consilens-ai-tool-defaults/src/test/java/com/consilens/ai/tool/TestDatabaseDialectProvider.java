package com.consilens.ai.tool;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.lang.reflect.Proxy;

/**
 * Test-only {@link DatabaseDialectProvider} implementations.
 *
 * <p>This module's test classpath does not bundle the connector plugins
 * (production distributions do), so these fixtures keep dialect lookup working
 * during tests.
 */
public abstract class TestDatabaseDialectProvider implements DatabaseDialectProvider {

    private final String connectorType;

    protected TestDatabaseDialectProvider(String connectorType) {
        this.connectorType = connectorType;
    }

    @Override
    public String getConnectorType() {
        return connectorType;
    }

    /** 与生产 provider 一样自报 URL 匹配规则，供 DialectFactory 做方言推断。 */
    @Override
    public boolean supportsJdbcUrl(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:" + connectorType + ":");
    }

    @Override
    public DatabaseDialect create() {
        return (DatabaseDialect) Proxy.newProxyInstance(
                DatabaseDialect.class.getClassLoader(),
                new Class<?>[]{DatabaseDialect.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getConnectorType":
                            return connectorType;
                        case "toString":
                            return "TestDatabaseDialect[" + connectorType + "]";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    public static final class MySQL extends TestDatabaseDialectProvider {
        public MySQL() {
            super("mysql");
        }
    }

    public static final class PostgreSQL extends TestDatabaseDialectProvider {
        public PostgreSQL() {
            super("postgresql");
        }
    }
}

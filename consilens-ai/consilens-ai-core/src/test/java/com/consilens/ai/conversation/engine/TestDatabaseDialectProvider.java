package com.consilens.ai.conversation.engine;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;

import java.lang.reflect.Proxy;

/**
 * Test-only {@link DatabaseDialectProvider} implementations.
 *
 * <p>This module's test classpath does not bundle the connector plugins (production
 * distributions do, via {@code consilens-connector-all}), so these fixtures keep
 * {@link SupportedConnectors} discovery working during tests.
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

    public static final class Oracle extends TestDatabaseDialectProvider {
        public Oracle() {
            super("oracle");
        }
    }

    public static final class SQLServer extends TestDatabaseDialectProvider {
        public SQLServer() {
            super("sqlserver");
        }
    }

    public static final class Presto extends TestDatabaseDialectProvider {
        public Presto() {
            super("presto");
        }
    }

    public static final class ClickHouse extends TestDatabaseDialectProvider {
        public ClickHouse() {
            super("clickhouse");
        }
    }

    public static final class Trino extends TestDatabaseDialectProvider {
        public Trino() {
            super("trino");
        }
    }

    public static final class TiDB extends TestDatabaseDialectProvider {
        public TiDB() {
            super("tidb");
        }
    }

    public static final class StarRocks extends TestDatabaseDialectProvider {
        public StarRocks() {
            super("starrocks");
        }
    }

    public static final class Doris extends TestDatabaseDialectProvider {
        public Doris() {
            super("doris");
        }
    }

    public static final class OceanBase extends TestDatabaseDialectProvider {
        public OceanBase() {
            super("oceanbase");
        }
    }
}

package com.consilens.connector.api;

import com.consilens.connector.api.write.TableWriteCompiler;

import java.util.Map;

/**
 * Database dialect interface - main entry point for database-specific
 * operations.
 * 
 * <p>
 * This interface follows the composition pattern, providing access to
 * specialized
 * component interfaces through getter methods:
 * <ul>
 * <li>{@link SqlQueryGenerator} - SQL query generation</li>
 * <li>{@link MetadataQueryGenerator} - Metadata query generation</li>
 * <li>{@link DataTypeHandler} - Data type handling and conversion</li>
 * <li>{@link TransactionManager} - Transaction management</li>
 * <li>{@link CapabilityProvider} - Database capability detection</li>
 * </ul>
 * 
 * <p>
 * <b>Usage Example:</b>
 * 
 * <pre>{@code
 * DatabaseDialect dialect = DialectFactory.getDialect("mysql");
 * 
 * // Get SQL query component
 * SqlQueryGenerator queryGen = dialect.getSqlQueryGenerator();
 * String sql = queryGen.getCountSQL("myschema", "mytable", null);
 * 
 * // Get metadata component
 * MetadataQueryGenerator metadataGen = dialect.getMetadataQueryGenerator();
 * String existsSQL = metadataGen.getTableExistsSQL("myschema", "mytable");
 * }</pre>
 * 
 * @since 1.0.0
 */
public interface DatabaseDialect {

    // ========== Core Properties ==========

    /**
     * Get the connector type identifier this dialect supports (e.g. "mysql", "postgresql").
     *
     * @return lowercase connector type string
     */
    String getConnectorType();

    // ========== Component Access Methods ==========

    /**
     * Get the SQL query generator component.
     * 
     * @return SQL query generator for this dialect
     */
    SqlQueryGenerator getSqlQueryGenerator();

    /**
     * Get the metadata query generator component.
     * 
     * @return metadata query generator for this dialect
     */
    MetadataQueryGenerator getMetadataQueryGenerator();

    /**
     * Get the data type handler component.
     * 
     * @return data type handler for this dialect
     */
    DataTypeHandler getDataTypeHandler();

    /**
     * Get the transaction manager component.
     * 
     * @return transaction manager for this dialect
     */
    TransactionManager getTransactionManager();

    /**
     * Get the capability provider component.
     * 
     * @return capability provider for this dialect
     */
    CapabilityProvider getCapabilityProvider();

    /**
     * Get the connection pool optimizer component.
     * 
     * @return connection pool optimizer for this dialect
     */
    ConnectionPoolOptimizer getConnectionPoolOptimizer();

    // ========== JDBC Support (datasource management / connection test) ==========

    /**
     * JDBC driver class name for this connector type
     * (e.g. "com.mysql.cj.jdbc.Driver"). Used by the server's connection-test
     * and metadata-exploration flows. Returns {@code null} for connector types
     * without JDBC support.
     *
     * @return fully qualified driver class name, or null
     */
    default String getJdbcDriverClassName() {
        return null;
    }

    /**
     * Default connection port used when no explicit port is given.
     *
     * @return default port for this connector type
     */
    default int getDefaultPort() {
        return 3306;
    }

    /**
     * Build the JDBC URL for a connection. The {@code params} map uses these
     * keys (all optional except {@code host}):
     * <ul>
     * <li>{@code host} - resolved IP literal of the target (required)</li>
     * <li>{@code port} - explicit port; falls back to {@link #getDefaultPort()}</li>
     * <li>{@code database} - database / schema / service name</li>
     * </ul>
     * Returns {@code null} when the connector type cannot build a JDBC URL.
     *
     * @param params connection parameters
     * @return JDBC URL, or null when unsupported
     */
    default String buildJdbcUrl(Map<String, Object> params) {
        return null;
    }

    /**
     * Get the datasource parameter template builder for this connector type.
     * <p>
     * Returns {@code null} when the dialect has no dedicated template; the
     * server then falls back to the generic JDBC template
     * (host/port/database/username/password/properties).
     *
     * @return datasource parameter template builder, or null
     */
    default DataSourceConfigBuilder getDataSourceConfigBuilder() {
        return null;
    }

    default TableWriteCompiler getTableWriteCompiler() {
        throw new UnsupportedOperationException("Table sink write is not supported for connectorType=" + getConnectorType());
    }
}

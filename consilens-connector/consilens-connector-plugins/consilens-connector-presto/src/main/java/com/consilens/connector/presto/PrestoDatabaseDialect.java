package com.consilens.connector.presto;

import com.consilens.connector.api.*;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * Presto database dialect implementation.
 */
public class PrestoDatabaseDialect extends AbstractDatabaseDialect {

    private final PrestoCapabilityProvider capabilityProvider;
    private final PrestoSqlQueryGenerator sqlQueryGenerator;
    private final PrestoMetadataQueryGenerator metadataQueryGenerator;
    private final PrestoDataTypeHandler dataTypeHandler;

    public PrestoDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new Presto database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public PrestoDatabaseDialect(Map<String, ?> normalizationConfig) {
        this.capabilityProvider = new PrestoCapabilityProvider();
        this.dataTypeHandler = new PrestoDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new PrestoSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new PrestoMetadataQueryGenerator(capabilityProvider);
    }

    @Override
    public String getConnectorType() {
        return "presto";
    }

    @Override
    public String getJdbcDriverClassName() {
        return "io.prestosql.jdbc.PrestoDriver";
    }

    @Override
    public int getDefaultPort() {
        return 8080;
    }

    @Override
    public String buildJdbcUrl(Map<String, Object> params) {
        String host = String.valueOf(params.get("host"));
        int port = params.get("port") != null ? ((Number) params.get("port")).intValue() : getDefaultPort();
        String database = params.get("database") != null ? String.valueOf(params.get("database")) : "";
        return "jdbc:presto://" + host + ":" + port + "/" + database;
    }

    @Override
    public CapabilityProvider getCapabilityProvider() {
        return capabilityProvider;
    }

    @Override
    public SqlQueryGenerator getSqlQueryGenerator() {
        return sqlQueryGenerator;
    }

    @Override
    public MetadataQueryGenerator getMetadataQueryGenerator() {
        return metadataQueryGenerator;
    }

    @Override
    public DataTypeHandler getDataTypeHandler() {
        return dataTypeHandler;
    }
}

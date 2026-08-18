package com.consilens.server.application.ai.metadata;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.MetadataQueryGenerator;
import com.consilens.server.api.dto.MetadataColumnDto;
import com.consilens.server.application.connection.DatasourceConnectionPolicy;
import com.consilens.server.application.connection.JdbcConnectionSupport;
import com.consilens.server.application.datasource.DialectSupport;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC metadata gateway for transient drafts. Reuses the same SSRF
 * validation, dialect URL building and whitelisted options as the persisted
 * datasource path; no datasource row is created.
 */
@Service
public class DefaultTransientDatasourceMetadataService implements TransientDatasourceMetadataService {

    private final DialectSupport dialectSupport;

    public DefaultTransientDatasourceMetadataService(DialectSupport dialectSupport) {
        this.dialectSupport = dialectSupport;
    }

    @Override
    public List<String> listDatabases(TransientConnectionSpec spec) {
        DatabaseDialect dialect = requireDialect(spec.getType());
        return withConnection(spec, dialect, connection ->
                queryStringList(connection, dialect.getMetadataQueryGenerator().getDatabaseListSQL()));
    }

    @Override
    public List<String> listTables(TransientConnectionSpec spec, String database) {
        DatabaseDialect dialect = requireDialect(spec.getType());
        return withConnection(spec, dialect, connection -> {
            MetadataQueryGenerator generator = dialect.getMetadataQueryGenerator();
            return queryStringList(connection, generator.getTablesSQL(schemaOf(spec, database)));
        });
    }

    @Override
    public List<MetadataColumnDto> listColumns(TransientConnectionSpec spec, String database, String table) {
        DatabaseDialect dialect = requireDialect(spec.getType());
        return withConnection(spec, dialect, connection -> {
            MetadataQueryGenerator generator = dialect.getMetadataQueryGenerator();
            return queryColumns(connection, generator.getTableColumnsSQL(schemaOf(spec, database), table));
        });
    }

    private DatabaseDialect requireDialect(String type) {
        return dialectSupport.find(type)
                .orElseThrow(() -> new IllegalArgumentException("unsupported datasource type: " + type));
    }

    private static String schemaOf(TransientConnectionSpec spec, String fallback) {
        Object schema = spec.getOptions() == null ? null : spec.getOptions().get("schema");
        if (schema != null && !String.valueOf(schema).isBlank()) {
            return String.valueOf(schema);
        }
        return fallback;
    }

    private <T> T withConnection(TransientConnectionSpec spec,
                                 DatabaseDialect dialect,
                                 JdbcQuery<T> query) {
        try {
            InetAddress target = JdbcConnectionSupport.validateTargetHost(spec.getHost());
            Map<String, Object> extra = new LinkedHashMap<>();
            if (spec.getOptions() != null) {
                for (String key : DatasourceConnectionPolicy.OPTION_WHITELIST) {
                    Object value = spec.getOptions().get(key);
                    if (value != null && !String.valueOf(value).isBlank()) {
                        extra.put(key, value);
                    }
                }
            }
            try (Connection connection = JdbcConnectionSupport.open(
                    dialect,
                    JdbcConnectionSupport.formatHost(target.getHostAddress()),
                    spec.getPort(),
                    spec.getDatabase(),
                    spec.getUsername(),
                    spec.getPassword() == null ? null : new String(spec.getPassword()),
                    JdbcConnectionSupport.DEFAULT_CONNECT_TIMEOUT_SECONDS,
                    extra)) {
                return query.apply(connection);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(JdbcConnectionSupport.safeMessage(e));
        }
    }

    private static List<String> queryStringList(Connection connection, String sql) {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException(JdbcConnectionSupport.safeMessage(e));
        }
        return result;
    }

    private static List<MetadataColumnDto> queryColumns(Connection connection, String sql) {
        List<MetadataColumnDto> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                columns.add(MetadataColumnDto.builder()
                        .name(rs.getString(1))
                        .dataType(rs.getString(2))
                        .nullable(rs.getObject(3) == null || rs.getBoolean(3))
                        .build());
            }
        } catch (Exception e) {
            throw new IllegalStateException(JdbcConnectionSupport.safeMessage(e));
        }
        return columns;
    }

    private interface JdbcQuery<T> {
        T apply(Connection connection) throws Exception;
    }
}

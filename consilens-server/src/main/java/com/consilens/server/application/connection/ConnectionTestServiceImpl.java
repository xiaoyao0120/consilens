package com.consilens.server.application.connection;

import com.consilens.server.api.dto.ConnectionTestRequest;
import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.application.datasource.DialectSupport;
import com.consilens.connector.api.DatabaseDialect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.sql.Connection;
import java.util.Properties;

/**
 * Connection test backed by the connector SPI: the driver class, JDBC URL
 * template and default port all come from {@link DatabaseDialect} (via
 * {@link DialectSupport}) instead of being hard-coded here.
 *
 * <p>The host is resolved once and the verified IP literal is used for the
 * actual connection (no address filtering; see
 * {@link JdbcConnectionSupport#validateTargetHost}).
 */
@Service
public class ConnectionTestServiceImpl implements ConnectionTestService {

    private static final int CONNECT_TIMEOUT_SECONDS = JdbcConnectionSupport.DEFAULT_CONNECT_TIMEOUT_SECONDS;

    /**
     * Options keys forwarded from the request into the dialect JDBC URL params.
     * Only these whitelisted keys pass through (sid/schema/properties), and they
     * never override host/port/database/username/password already resolved from
     * the request fields.
     */
    private static final java.util.Set<String> OPTION_WHITELIST = java.util.Set.of("sid", "schema", "properties");

    /** 与 DataSourceServiceImpl 一致的名称白名单(host/database/sid/schema)。 */
    private static final String NAME_CHARS_PATTERN = "[A-Za-z0-9_.$: \\-]{1,256}";

    /** properties 连接属性字符白名单。 */
    private static final String PROPERTIES_PATTERN = "[A-Za-z0-9_=.,&:\\- ]{0,1024}";

    private final ConnectionOpener connectionOpener;
    private final DialectSupport dialectSupport;

    @Autowired
    public ConnectionTestServiceImpl(DialectSupport dialectSupport) {
        this(ConnectionTestServiceImpl::openConnection, dialectSupport);
    }

    ConnectionTestServiceImpl(ConnectionOpener connectionOpener, DialectSupport dialectSupport) {
        this.connectionOpener = connectionOpener;
        this.dialectSupport = dialectSupport;
    }

    @Override
    public ConnectionTestResponse test(ConnectionTestRequest request) {
        long startedAt = System.currentTimeMillis();
        String url;
        Properties properties;
        try {
            DatabaseDialect dialect = dialectSupport.find(request.getType())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unsupported datasource type: " + request.getType()));
            // 校验与建连使用同一解析结果（IP 字面量），避免 DNS rebinding TOCTOU
            InetAddress target = JdbcConnectionSupport.validateTargetHost(request.getHost());
            url = JdbcConnectionSupport.buildJdbcUrl(
                    dialect,
                    JdbcConnectionSupport.formatHost(target.getHostAddress()),
                    request.getPort(),
                    request.getDatabase(),
                    whitelistedOptions(request.getOptions()));
            properties = buildProperties(request.getUsername(), request.getPassword());
        } catch (Exception exception) {
            return failure(startedAt, JdbcConnectionSupport.safeMessage(exception));
        }
        try (Connection connection = connectionOpener.open(url, properties)) {
            if (connection == null || connection.isClosed()) {
                return failure(startedAt, "connection returned no usable session");
            }
            return ConnectionTestResponse.builder()
                    .success(true)
                    .latencyMs(System.currentTimeMillis() - startedAt)
                    .error(null)
                    .build();
        } catch (Exception exception) {
            return failure(startedAt, JdbcConnectionSupport.safeMessage(exception));
        }
    }

    private static Properties buildProperties(String username, String password) {        Properties properties = new Properties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        properties.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS * 1000));
        properties.setProperty("loginTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        return properties;
    }

    private static Connection openConnection(String url, Properties properties) throws java.sql.SQLException {
        java.sql.DriverManager.setLoginTimeout(CONNECT_TIMEOUT_SECONDS);
        return java.sql.DriverManager.getConnection(url, properties);
    }

    /**
     * 从请求 options 中提取白名单键(sid/schema/properties)用于方言 JDBC URL 参数。
     * 其余键一律丢弃,避免任意参数注入;sid/schema 复用名称白名单字符校验。
     */
    private static java.util.Map<String, Object> whitelistedOptions(java.util.Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        java.util.Map<String, Object> filtered = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Object> entry : options.entrySet()) {
            if (!OPTION_WHITELIST.contains(entry.getKey())) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            if (text.isBlank()) {
                continue;
            }
            if (("sid".equals(entry.getKey()) || "schema".equals(entry.getKey()))
                    && !text.matches(NAME_CHARS_PATTERN)) {
                throw new IllegalArgumentException("options." + entry.getKey() + " contains invalid characters");
            }
            if ("properties".equals(entry.getKey()) && !text.matches(PROPERTIES_PATTERN)) {
                throw new IllegalArgumentException("options.properties contains invalid characters");
            }
            filtered.put(entry.getKey(), value);
        }
        return filtered.isEmpty() ? null : filtered;
    }

    private ConnectionTestResponse failure(long startedAt, String message) {
        return ConnectionTestResponse.builder()
                .success(false)
                .latencyMs(System.currentTimeMillis() - startedAt)
                .error(message)
                .build();
    }
}

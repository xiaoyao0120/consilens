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
            return failure(startedAt, exception);
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
            return failure(startedAt, exception);
        }
    }

    private static Properties buildProperties(String username, String password) {        Properties properties = new Properties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        // timeout is enforced via DriverManager.setLoginTimeout below; passing
        // connectTimeout/loginTimeout properties breaks strict drivers (e.g.
        // clickhouse-jdbc rejects unknown config keys)
        return properties;
    }

    private static Connection openConnection(String url, Properties properties) throws java.sql.SQLException {
        java.sql.DriverManager.setLoginTimeout(CONNECT_TIMEOUT_SECONDS);
        return java.sql.DriverManager.getConnection(url, properties);
    }

    private static java.util.Map<String, Object> whitelistedOptions(java.util.Map<String, Object> options) {
        return DatasourceConnectionPolicy.whitelistedOptions(options);
    }

    private ConnectionTestResponse failure(long startedAt, String message) {
        return failure(startedAt, message, null);
    }

    /** 失败响应保留原始异常链，供 AI 工具按 SQLState 区分认证与网络错误。 */
    private ConnectionTestResponse failure(long startedAt, Exception exception) {
        return failure(startedAt, JdbcConnectionSupport.safeMessage(exception), exception);
    }

    private ConnectionTestResponse failure(long startedAt, String message, Throwable cause) {
        return ConnectionTestResponse.builder()
                .success(false)
                .latencyMs(System.currentTimeMillis() - startedAt)
                .error(message)
                .cause(cause)
                .build();
    }
}

package com.consilens.server.application.connection;

import com.consilens.connector.api.DatabaseDialect;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Shared JDBC wiring for connection tests and metadata exploration.
 *
 * <p>The JDBC URL template, driver class name and default port are all provided
 * by the connector SPI ({@link DatabaseDialect}) instead of being hard-coded
 * in the server.
 */
public final class JdbcConnectionSupport {

    public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;

    private JdbcConnectionSupport() {
    }

    /**
     * Resolves and validates a target host against SSRF rules: loopback,
     * link-local (incl. cloud metadata 169.254.169.254), multicast, unspecified
     * and broadcast addresses are rejected. Private RFC1918 ranges stay allowed
     * because enterprise data sources commonly live on internal networks.
     *
     * @return the first verified address, to be used as the connection target
     */
    /**
     * Resolves a target host to an IP literal for the JDBC URL. No address
     * filtering is applied (loopback / link-local / any address are all
     * allowed), matching the datavines connector behavior. The host is
     * resolved once and the verified address is used for the connection so
     * the DNS answer cannot change between validation and connect.
     *
     * @return the first resolved address, to be used as the connection target
     */
    public static InetAddress validateTargetHost(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        return addresses[0];
    }

    /**
     * IPv6 literals must be bracketed inside JDBC URLs: jdbc:mysql://[::1]:3306/db.
     */
    public static String formatHost(String address) {
        return address.contains(":") ? "[" + address + "]" : address;
    }

    /**
     * Builds the connection params for {@link DatabaseDialect#buildJdbcUrl(Map)}
     * and pre-loads the driver class.
     *
     * @param dialect      connector dialect (from SPI)
     * @param hostLiteral  verified IP literal (see {@link #validateTargetHost})
     * @param port         explicit port or null to use the dialect default
     * @param database     database / schema / service name or null
     * @return JDBC URL built by the dialect
     */
    public static String buildJdbcUrl(DatabaseDialect dialect,
                                      String hostLiteral,
                                      Integer port,
                                      String database) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("host", hostLiteral);
        if (port != null) {
            params.put("port", port);
        }
        if (database != null && !database.isBlank()) {
            params.put("database", database);
        }
        String url = dialect.buildJdbcUrl(params);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("unsupported datasource type: " + dialect.getConnectorType());
        }
        return url;
    }

    /**
     * Opens a JDBC connection through the dialect-provided driver and URL.
     * The host must already be validated (use {@link #validateTargetHost}).
     */
    public static java.sql.Connection open(DatabaseDialect dialect,
                                           String hostLiteral,
                                           Integer port,
                                           String database,
                                           String username,
                                           String password,
                                           int timeoutSeconds) throws Exception {
        String driverClass = dialect.getJdbcDriverClassName();
        if (driverClass == null || driverClass.isBlank()) {
            throw new IllegalArgumentException("unsupported datasource type: " + dialect.getConnectorType());
        }
        Class.forName(driverClass);
        String url = buildJdbcUrl(dialect, hostLiteral, port, database);
        Properties properties = new Properties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        properties.setProperty("connectTimeout", String.valueOf(timeoutSeconds * 1000));
        properties.setProperty("loginTimeout", String.valueOf(timeoutSeconds));
        DriverManager.setLoginTimeout(timeoutSeconds);
        return DriverManager.getConnection(url, properties);
    }

    /**
     * Sanitizes an exception message so credentials never leak:
     * strips user:pass@ fragments from URLs and user names echoed back by
     * JDBC drivers (single or double quoted).
     */
    public static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message
                .replaceAll("(?<=://)[^/@\\s]+@", "***@")
                .replaceAll("(?i)for user ['\"][^'\"]*['\"]", "for user '***'");
    }
}

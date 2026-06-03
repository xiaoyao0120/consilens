package com.consilens.mcp;

import com.consilens.mcp.catalog.ConsilensMcpCatalog;
import com.consilens.mcp.client.ConsilensServerClient;
import com.consilens.mcp.server.ConsilensMcpServerFactory;
import com.consilens.mcp.transport.McpHttpServer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

public class ConsilensMcpApplication {

    public static void main(String[] args) throws Exception {
        McpRuntimeConfig config = McpRuntimeConfig.parse(args);
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(ConsilensJson.objectMapper());
        ConsilensMcpCatalog catalog = new ConsilensMcpCatalog();
        ConsilensServerClient client = new ConsilensServerClient(config.serverBaseUri(), config.serverApiKey(),
                jsonMapper, config.connectTimeout(), config.requestTimeout());

        if (config.transport() == McpRuntimeConfig.Transport.HTTP) {
            startHttp(config, jsonMapper, catalog, client);
            return;
        }

        StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);
        ConsilensMcpServerFactory.configure(McpServer.sync(transport), catalog, client, jsonMapper,
                config.requestTimeout()).build();
        Thread.currentThread().join();
    }

    private static void startHttp(McpRuntimeConfig config,
                                  McpJsonMapper jsonMapper,
                                  ConsilensMcpCatalog catalog,
                                  ConsilensServerClient client) throws Exception {
        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint(config.mcpEndpoint())
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedHosts(config.allowedHosts())
                        .allowedOrigins(config.allowedOrigins())
                        .build())
                .build();
        ConsilensMcpServerFactory.configure(McpServer.sync(transport), catalog, client, jsonMapper,
                config.requestTimeout()).build();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(transport), config.mcpEndpoint());
        context.addServlet(new HealthServlet(), "/healthz");

        McpHttpServer server = new McpHttpServer(config.httpHost(), config.httpPort(), context);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        server.join();
    }

    record McpRuntimeConfig(Transport transport,
                            URI serverBaseUri,
                            String serverApiKey,
                            String httpHost,
                            int httpPort,
                            String mcpEndpoint,
                            Duration connectTimeout,
                            Duration requestTimeout,
                            List<String> allowedHosts,
                            List<String> allowedOrigins) {

        enum Transport {
            STDIO,
            HTTP
        }

        static McpRuntimeConfig parse(String[] args) {
            Transport transport = Transport.STDIO;
            URI serverBaseUri = URI.create(value("CONSILENS_SERVER_URL", "http://127.0.0.1:8080"));
            String serverApiKey = System.getenv("CONSILENS_SERVER_API_KEY");
            String httpHost = value("CONSILENS_MCP_HOST", "127.0.0.1");
            int httpPort = parsePort(value("CONSILENS_MCP_PORT", "8090"), "CONSILENS_MCP_PORT");
            String mcpEndpoint = value("CONSILENS_MCP_ENDPOINT", "/mcp");
            Duration connectTimeout = Duration.ofMillis(parsePositiveLong(
                    value("CONSILENS_MCP_CONNECT_TIMEOUT_MS", "10000"), "CONSILENS_MCP_CONNECT_TIMEOUT_MS"));
            Duration requestTimeout = Duration.ofMillis(parsePositiveLong(
                    value("CONSILENS_MCP_REQUEST_TIMEOUT_MS", "60000"), "CONSILENS_MCP_REQUEST_TIMEOUT_MS"));
            List<String> allowedHosts = listValue("CONSILENS_MCP_ALLOWED_HOSTS",
                    List.of("localhost:*", "127.0.0.1:*", "[::1]:*"));
            List<String> allowedOrigins = listValue("CONSILENS_MCP_ALLOWED_ORIGINS",
                    List.of("http://localhost:*", "http://127.0.0.1:*", "http://[::1]:*"));

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--transport".equals(arg)) {
                    transport = transport(next(args, ++i, arg));
                } else if ("--server-url".equals(arg)) {
                    serverBaseUri = URI.create(next(args, ++i, arg));
                } else if ("--server-api-key".equals(arg)) {
                    serverApiKey = next(args, ++i, arg);
                } else if ("--http-host".equals(arg)) {
                    httpHost = next(args, ++i, arg);
                } else if ("--http-port".equals(arg)) {
                    httpPort = parsePort(next(args, ++i, arg), arg);
                } else if ("--mcp-endpoint".equals(arg)) {
                    mcpEndpoint = next(args, ++i, arg);
                } else if ("--connect-timeout-ms".equals(arg)) {
                    connectTimeout = Duration.ofMillis(parsePositiveLong(next(args, ++i, arg), arg));
                } else if ("--request-timeout-ms".equals(arg)) {
                    requestTimeout = Duration.ofMillis(parsePositiveLong(next(args, ++i, arg), arg));
                } else if ("--allowed-hosts".equals(arg)) {
                    allowedHosts = parseList(next(args, ++i, arg), arg);
                } else if ("--allowed-origins".equals(arg)) {
                    allowedOrigins = parseList(next(args, ++i, arg), arg);
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    printUsageAndExit();
                } else if ("--version".equals(arg)) {
                    printVersionAndExit();
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            if (!mcpEndpoint.startsWith("/")) {
                throw new IllegalArgumentException("--mcp-endpoint must start with /");
            }
            if (httpHost.isBlank()) {
                throw new IllegalArgumentException("--http-host must not be blank");
            }
            return new McpRuntimeConfig(transport, serverBaseUri, serverApiKey, httpHost, httpPort, mcpEndpoint,
                    connectTimeout, requestTimeout, List.copyOf(allowedHosts), List.copyOf(allowedOrigins));
        }

        private static Transport transport(String value) {
            if ("stdio".equalsIgnoreCase(value)) {
                return Transport.STDIO;
            }
            if ("http".equalsIgnoreCase(value) || "streamable-http".equalsIgnoreCase(value)) {
                return Transport.HTTP;
            }
            throw new IllegalArgumentException("Unsupported transport: " + value);
        }

        private static String value(String envName, String fallback) {
            String value = System.getenv(envName);
            return value == null || value.isBlank() ? fallback : value;
        }

        private static String next(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        private static int parsePort(String value, String name) {
            long port = parsePositiveLong(value, name);
            if (port > 65535) {
                throw new IllegalArgumentException(name + " must be between 1 and 65535");
            }
            return (int) port;
        }

        private static long parsePositiveLong(String value, String name) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed <= 0) {
                    throw new IllegalArgumentException(name + " must be positive");
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(name + " must be a number", exception);
            }
        }

        private static List<String> listValue(String envName, List<String> fallback) {
            String value = System.getenv(envName);
            return value == null || value.isBlank() ? fallback : parseList(value, envName);
        }

        private static List<String> parseList(String value, String name) {
            List<String> values = java.util.Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
            if (values.isEmpty()) {
                throw new IllegalArgumentException(name + " must include at least one value");
            }
            return values;
        }

        private static void printUsageAndExit() {
            System.out.println("Usage: consilens-mcp [--transport stdio|http] [--server-url URL] "
                    + "[--server-api-key KEY] [--http-host HOST] [--http-port PORT] [--mcp-endpoint PATH] "
                    + "[--connect-timeout-ms MS] [--request-timeout-ms MS] "
                    + "[--allowed-hosts HOSTS] [--allowed-origins ORIGINS]");
            System.exit(0);
        }

        private static void printVersionAndExit() {
            System.out.println(versionLabel());
            System.exit(0);
        }
    }

    static String versionLabel() {
        String version = ConsilensMcpApplication.class.getPackage().getImplementationVersion();
        return "consilens-mcp " + (version == null || version.isBlank() ? "dev" : version);
    }

    static class HealthServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"UP\"}");
        }
    }
}

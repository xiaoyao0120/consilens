package com.consilens.cli.command;

import com.consilens.cli.mcp.ConsilensMcpToolRegistry;
import com.consilens.cli.mcp.ConsilensServerMcpToolExecutor;
import com.consilens.cli.mcp.McpProtocolServer;
import com.consilens.cli.mcp.McpStdioServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "mcp",
        description = "Run the stateless MCP adapter backed by consilens-server",
        mixinStandardHelpOptions = true,
        subcommands = {
                McpCommand.ToolsCommand.class,
                McpCommand.StdioCommand.class
        }
)
public class McpCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    @Command(name = "tools", description = "List Consilens MCP tools", mixinStandardHelpOptions = true)
    public static class ToolsCommand implements Callable<Integer> {

        private final ObjectMapper objectMapper;

        @Option(names = "--format", defaultValue = "text", description = "Output format: text or json")
        private String format;

        @Spec
        private CommandSpec spec;

        public ToolsCommand() {
            this(new ObjectMapper());
        }

        ToolsCommand(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public Integer call() throws Exception {
            if ("json".equalsIgnoreCase(format)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tools", ConsilensMcpToolRegistry.definitions());
                spec.commandLine().getOut().println(objectMapper.writeValueAsString(payload));
                return 0;
            }
            if (!"text".equalsIgnoreCase(format)) {
                spec.commandLine().getErr().println("Unsupported format: " + format + ". Use text or json.");
                return 2;
            }
            PrintWriter out = spec.commandLine().getOut();
            out.println("# Consilens MCP Tools");
            ConsilensMcpToolRegistry.definitions()
                    .forEach(tool -> out.println("- " + tool.getName() + " -> " + tool.getHttpMethod()
                            + " " + tool.getApiPath()));
            out.flush();
            return 0;
        }
    }

    @Command(name = "stdio", description = "Serve MCP over newline-delimited stdio JSON-RPC", mixinStandardHelpOptions = true)
    public static class StdioCommand implements Callable<Integer> {

        @Option(names = "--server-url", defaultValue = "http://127.0.0.1:18080",
                description = "consilens-server base URL")
        private String serverUrl;

        @Option(names = "--auth-token", description = "Bearer token for consilens-server")
        private String authToken;

        @Option(names = "--auth-token-env", defaultValue = "CONSILENS_SERVER_TOKEN",
                description = "Environment variable used when --auth-token is omitted")
        private String authTokenEnv;

        @Override
        public Integer call() throws Exception {
            ObjectMapper objectMapper = new ObjectMapper();
            String effectiveToken = authToken;
            if ((effectiveToken == null || effectiveToken.isBlank())
                    && authTokenEnv != null && !authTokenEnv.isBlank()) {
                effectiveToken = System.getenv(authTokenEnv);
            }
            ConsilensServerMcpToolExecutor executor =
                    new ConsilensServerMcpToolExecutor(serverUrl, effectiveToken, objectMapper);
            new McpStdioServer(new McpProtocolServer(executor, objectMapper), objectMapper)
                    .serve(System.in, System.out);
            return 0;
        }
    }
}

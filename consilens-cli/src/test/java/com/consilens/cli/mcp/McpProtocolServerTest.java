package com.consilens.cli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpProtocolServerTest {

    @Test
    void shouldListToolsAndCallServerApiBackedTool() {
        AtomicReference<String> calledTool = new AtomicReference<>();
        McpProtocolServer server = new McpProtocolServer(new ConsilensMcpToolExecutor() {
            @Override
            public Map<String, Object> execute(String toolName, Map<String, Object> arguments) {
                calledTool.set(toolName);
                return Map.of("ok", true, "arguments", arguments);
            }

            @Override
            public Map<String, Object> readResource(String uri) {
                return Map.of("uri", uri);
            }
        }, new ObjectMapper());

        Map<String, Object> list = server.handle(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));
        assertEquals(1, list.get("id"));
        assertTrue(String.valueOf(list.get("result")).contains("consilens.plan.config"));

        Map<String, Object> call = server.handle(Map.of(
                "jsonrpc", "2.0",
                "id", 2,
                "method", "tools/call",
                "params", Map.of(
                        "name", "consilens.validate.config",
                        "arguments", Map.of("configArtifactId", "artifact-1"))));
        assertEquals("consilens.validate.config", calledTool.get());
        assertTrue(String.valueOf(call.get("result")).contains("artifact-1"));
    }
}

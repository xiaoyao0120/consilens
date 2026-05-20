package com.consilens.cli.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class McpStdioServer {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private final McpProtocolServer protocolServer;
    private final ObjectMapper objectMapper;

    public McpStdioServer(McpProtocolServer protocolServer, ObjectMapper objectMapper) {
        this.protocolServer = protocolServer;
        this.objectMapper = objectMapper;
    }

    public void serve(InputStream input, PrintStream output) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> request = objectMapper.readValue(line, MAP_TYPE);
            Map<String, Object> response = protocolServer.handle(request);
            if (response != null) {
                output.println(objectMapper.writeValueAsString(response));
                output.flush();
            }
        }
    }
}

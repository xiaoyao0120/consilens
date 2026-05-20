package com.consilens.cli.mcp;

import java.util.Map;

public interface ConsilensMcpToolExecutor {

    Map<String, Object> execute(String toolName, Map<String, Object> arguments) throws Exception;

    Map<String, Object> readResource(String uri) throws Exception;
}

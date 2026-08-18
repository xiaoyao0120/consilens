package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class FindDatasourceOutput {
    boolean found;
    String id;
    String name;
    String type;
    Map<String, Object> paramWithoutSecret;
}

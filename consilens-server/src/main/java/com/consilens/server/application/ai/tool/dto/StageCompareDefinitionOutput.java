package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class StageCompareDefinitionOutput {
    String taskName;
    String sourceDatabase;
    String sourceTable;
    String targetDatabase;
    String targetTable;
    List<String> keys;
    List<Map<String, Object>> keyMappings;
    List<Map<String, Object>> fieldMappings;
    List<String> ignoreColumns;
    Map<String, Object> strategyHints;
    String configTemplateDigest;
}

package com.consilens.agent.api.state;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
@Jacksonized
public class AgentComparisonDraft {
    List<String> keys;
    List<AgentFieldMapping> keyMappings;
    List<AgentFieldMapping> fieldMappings;
    List<String> ignoreColumns;
    Map<String, Object> strategyHints;
    String configTemplateDigest;
}

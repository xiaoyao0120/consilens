package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class StageDatasourceDraftOutput {
    String draftId;
    String datasourceId;
    String name;
    String type;
    List<String> missingNonSecretFields;
    List<String> requiredSecretFields;
    boolean readyForSecret;
    Map<String, Object> mergedNonSecretParams;
    String secretRequestId;
}

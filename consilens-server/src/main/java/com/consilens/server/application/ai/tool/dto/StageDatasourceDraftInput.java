package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import lombok.Value;

import java.util.Map;

@Value
@Builder
@Jacksonized
public class StageDatasourceDraftInput {
    AgentDraftSide side;
    String name;
    String type;
    Map<String, Object> nonSecretParams;
    /** 可选：复用已有数据源（传数据源 id）时跳过新建草稿/凭据流程。 */
    String datasourceId;
}

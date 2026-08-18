package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import lombok.Value;

@Value
@Builder
@Jacksonized
public class ListDatasourcesInput {
    /** 可选：按名称模糊过滤。 */
    String keyword;
}

package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import lombok.Value;

@Value
@Builder
@Jacksonized
public class ProbeDatasourceInput {
    String draftId;
}

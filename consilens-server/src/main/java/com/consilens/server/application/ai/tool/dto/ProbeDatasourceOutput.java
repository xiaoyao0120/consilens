package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ProbeDatasourceOutput {
    String draftId;
    boolean success;
    String safeMessage;
    String errorCode;
    String paramDigest;
    String newSecretRequestId;
}

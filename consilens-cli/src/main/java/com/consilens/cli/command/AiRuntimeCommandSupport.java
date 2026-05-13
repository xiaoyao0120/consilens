package com.consilens.cli.command;

import com.consilens.ai.runtime.model.AiTurnResult;

import java.util.UUID;

final class AiRuntimeCommandSupport {

    private AiRuntimeCommandSupport() {
    }

    static String effectiveSessionId(String sessionId) {
        return sessionId == null || sessionId.trim().isEmpty()
                ? "ai-" + UUID.randomUUID()
                : sessionId.trim();
    }

    static int exitCode(AiTurnResult result) {
        if (result.getStatus() == AiTurnResult.Status.COMPLETED) {
            return 0;
        }
        if (result.getStatus() == AiTurnResult.Status.REQUIRES_APPROVAL) {
            return 2;
        }
        return 1;
    }

    static void print(String sessionId, AiTurnResult result) {
        System.out.println("[AI RUNTIME] session=" + sessionId);
        System.out.println(result.getMessage());
    }
}

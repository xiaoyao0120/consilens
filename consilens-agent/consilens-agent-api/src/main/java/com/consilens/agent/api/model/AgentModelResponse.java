package com.consilens.agent.api.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Canonical model outcome. Failed responses carry {@code errorCode} instead of
 * text; the core turns them into RUN_FAILED events and never executes tools.
 */
@Value
@Builder
public class AgentModelResponse {
    String text;
    /**
     * Reasoning models (e.g. deepseek-v4-flash thinking mode) require the
     * previous assistant reasoning_content to be passed back on the next
     * request; it is persisted with events and replayed by the assembler.
     */
    String reasoningContent;
    List<AgentModelToolCall> toolCalls;
    AgentModelFinishReason finishReason;
    AgentModelUsage usage;

    /**
     * False when the provider reported finishReason=LENGTH with tool calls:
     * arguments may be truncated, so no tool call may be executed.
     */
    boolean toolArgumentsTrusted;

    String errorCode;
    boolean retryable;
    String safeMessage;
    long durationMillis;

    public boolean isFailed() {
        return errorCode != null;
    }

    public static AgentModelResponse success(String text,
                                             List<AgentModelToolCall> toolCalls,
                                             AgentModelFinishReason finishReason,
                                             AgentModelUsage usage,
                                             boolean toolArgumentsTrusted,
                                             long durationMillis) {
        return success(text, toolCalls, finishReason, usage, toolArgumentsTrusted,
                durationMillis, null);
    }

    public static AgentModelResponse success(String text,
                                             List<AgentModelToolCall> toolCalls,
                                             AgentModelFinishReason finishReason,
                                             AgentModelUsage usage,
                                             boolean toolArgumentsTrusted,
                                             long durationMillis,
                                             String reasoningContent) {
        return AgentModelResponse.builder()
                .text(text)
                .reasoningContent(reasoningContent)
                .toolCalls(toolCalls == null ? List.of() : List.copyOf(toolCalls))
                .finishReason(finishReason)
                .usage(usage)
                .toolArgumentsTrusted(toolArgumentsTrusted)
                .durationMillis(durationMillis)
                .build();
    }

    public static AgentModelResponse failure(String errorCode,
                                             boolean retryable,
                                             String safeMessage,
                                             long durationMillis) {
        return AgentModelResponse.builder()
                .errorCode(errorCode)
                .retryable(retryable)
                .safeMessage(safeMessage)
                .finishReason(AgentModelFinishReason.ERROR)
                .toolCalls(List.of())
                .durationMillis(durationMillis)
                .build();
    }
}

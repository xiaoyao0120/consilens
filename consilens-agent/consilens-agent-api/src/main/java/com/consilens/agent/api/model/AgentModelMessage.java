package com.consilens.agent.api.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * One message sent to the model. For TOOL role messages, {@code toolCallId}
 * links the result to the originating model tool call.
 */
@Value
@Builder
public class AgentModelMessage {
    AgentMessageRole role;
    String content;
    /**
     * Reasoning content of a prior assistant turn, replayed for DeepSeek
     * thinking mode. Only set on ASSISTANT messages.
     */
    String reasoningContent;
    String toolCallId;
    List<AgentModelToolCall> toolCalls;

    /**
     * Optional structured content for tool results that must be readable by
     * the model without text serialization loss.
     */
    JsonNode structuredContent;
}

package com.consilens.ai.tool;

import com.consilens.ai.chat.ConversationContext;
import com.consilens.ai.spi.AIAnalyzer;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Context passed to each tool invocation.
 */
@Data
@Builder
public class ToolContext {

    /** The active conversation context. */
    private ConversationContext conversation;

    /** The AI analyzer available for this session. */
    private AIAnalyzer analyzer;

    /** Optional planner/runtime attributes available to tools. */
    private Map<String, Object> attributes;
}

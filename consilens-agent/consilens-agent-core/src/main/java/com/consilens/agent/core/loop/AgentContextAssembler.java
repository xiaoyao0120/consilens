package com.consilens.agent.core.loop;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.model.AgentMessageRole;
import com.consilens.agent.api.model.AgentModelToolCall;
import com.consilens.agent.api.model.AgentModelMessage;
import com.consilens.agent.api.model.AgentModelRequest;
import com.consilens.agent.api.model.AgentModelToolDefinition;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.core.tool.AgentToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the minimal model context (section 7 and 28.4): stable system rules,
 * structured working state, recent model-visible events, tools allowed in the
 * current stage. Events are the only message source; text from the model is
 * never treated as state.
 */
public final class AgentContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(AgentContextAssembler.class);

    private final String systemPrompt;
    private final ObjectMapper mapper;
    private final int maxRecentEvents;

    public AgentContextAssembler(String systemPrompt, int maxRecentEvents) {
        this(systemPrompt, maxRecentEvents, new ObjectMapper());
    }

    public AgentContextAssembler(String systemPrompt, int maxRecentEvents, ObjectMapper mapper) {
        this.systemPrompt = systemPrompt;
        this.maxRecentEvents = maxRecentEvents;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.mapper.registerModule(new JavaTimeModule());
    }

    public AgentModelRequest build(AgentWorkingState state,
                                   List<AgentEvent> events,
                                   AgentToolRegistry registry) {
        List<AgentModelMessage> messages = new ArrayList<>();
        String stagePrompt = systemPrompt + "\n\nCurrent workflow stage: " + state.getStage();
        messages.add(AgentModelMessage.builder().role(AgentMessageRole.SYSTEM).content(stagePrompt).build());
        messages.add(AgentModelMessage.builder()
                .role(AgentMessageRole.SYSTEM)
                .content("Working state (facts only, do not invent missing values):\n"
                        + mapper.valueToTree(state).toString())
                .build());

        List<AgentEvent> visible = new ArrayList<>();
        for (AgentEvent event : events) {
            if (event.getVisibility().visibleToModel()) {
                visible.add(event);
            }
        }
        int from = Math.max(0, visible.size() - maxRecentEvents);
        AgentEvent prevVisibleEvent = null;
        for (AgentEvent event : visible.subList(from, visible.size())) {
            AgentModelMessage message = toMessage(event);
            if (message == null) {
                continue;
            }
            // 同一轮（turnId 相同）的 assistant 文字与其 tool call 合并为一条
            // assistant 消息，保证 reasoning_content 与 tool_calls 配对正确，
            // 也满足 OpenAI 兼容历史中 tool 消息必须跟在 assistant tool_calls 之后。
            boolean sameTurn = prevVisibleEvent != null
                    && sameTurnId(prevVisibleEvent, event);
            if (event.getType() == AgentEventType.TOOL_PROPOSED && sameTurn && !messages.isEmpty()) {
                AgentModelMessage last = messages.get(messages.size() - 1);
                if (last.getRole() == AgentMessageRole.ASSISTANT) {
                    List<AgentModelToolCall> merged = new ArrayList<>(
                            last.getToolCalls() == null ? List.of() : last.getToolCalls());
                    merged.addAll(message.getToolCalls());
                    messages.set(messages.size() - 1, AgentModelMessage.builder()
                            .role(AgentMessageRole.ASSISTANT)
                            .content(last.getContent())
                            .reasoningContent(last.getReasoningContent())
                            .toolCalls(merged)
                            .build());
                    continue;
                }
            }
            messages.add(message);
            prevVisibleEvent = event;
        }

        List<AgentModelToolDefinition> definitions = new ArrayList<>();
        for (AgentTool<?, ?> tool : registry.all()) {
            if (tool.descriptor().getAllowedStages().isEmpty()
                    || tool.descriptor().getAllowedStages().contains(state.getStage())) {
                definitions.add(AgentModelToolDefinition.builder()
                        .name(tool.descriptor().getName())
                        .description(tool.descriptor().getDescription())
                        .inputSchema(tool.inputSchema())
                        .build());
            }
        }
        log.debug("agent context built: stage={} systemChars={} modelMessages={} "
                        + "visibleEvents={} tools={}",
                state.getStage(),
                stagePrompt == null ? 0 : stagePrompt.length(),
                messages.size(),
                visible.size(),
                definitions.size());
        return AgentModelRequest.builder()
                .messages(messages)
                .toolDefinitions(definitions)
                .build();
    }

    private AgentModelMessage toMessage(AgentEvent event) {
        JsonNode payload = event.getPayload();
        switch (event.getType()) {
            case USER_MESSAGE:
            case STEERING_QUEUED:
                return AgentModelMessage.builder()
                        .role(AgentMessageRole.USER)
                        .content(payload.path("text").asText(""))
                        .build();
            case ASSISTANT_MESSAGE:
                return AgentModelMessage.builder()
                        .role(AgentMessageRole.ASSISTANT)
                        .content(payload.path("text").asText(""))
                        .reasoningContent(payload.path("reasoningContent").asText(null))
                        .build();
            case TOOL_PROPOSED:
                JsonNode arguments = payload.get("arguments");
                return AgentModelMessage.builder()
                        .role(AgentMessageRole.ASSISTANT)
                        .content("")
                        .reasoningContent(payload.path("reasoningContent").asText(null))
                        .toolCalls(java.util.List.of(AgentModelToolCall.builder()
                                .id(payload.path("callId").asText(null))
                                .name(payload.path("toolName").asText(""))
                                .arguments(arguments == null ? "{}" : arguments.toString())
                                .index(0)
                                .build()))
                        .build();
            case TOOL_COMPLETED:
                // 模型需要同时看到一句话摘要与结构化明细（候选列表/元数据）
                String safeSummary = payload.path("safeSummary").asText("");
                String resultJson = payload.path("resultJson").asText("");
                String toolContent = resultJson.isBlank()
                        ? safeSummary
                        : (safeSummary + "\n" + resultJson).trim();
                return AgentModelMessage.builder()
                        .role(AgentMessageRole.TOOL)
                        .toolCallId(payload.path("callId").asText(null))
                        .content(toolContent)
                        .build();
            case TOOL_BLOCKED:
                return AgentModelMessage.builder()
                        .role(AgentMessageRole.TOOL)
                        .toolCallId(payload.path("callId").asText(null))
                        .content(payload.path("safeMessage").asText("blocked"))
                        .build();
            default:
                return null;
        }
    }

    private static boolean sameTurnId(AgentEvent first, AgentEvent second) {
        if (first.getTurnId() == null || second.getTurnId() == null) {
            return false;
        }
        return first.getTurnId().equals(second.getTurnId());
    }
}

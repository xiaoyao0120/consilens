package com.consilens.agent.core.tool;

import com.consilens.agent.api.model.AgentModelToolCall;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.core.security.CanonicalJsonDigester;
import com.consilens.agent.core.security.SensitiveValueGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

/**
 * Fixed preflight order (section 22.1): existence, JSON schema, stage,
 * permission, sensitive-field scan, canonical digests, idempotency replay.
 * Any failure blocks the tool call; nothing reaches the tool.
 */
public final class AgentToolPreflight {

    private final AgentToolRegistry registry;
    private final ObjectMapper mapper;

    public AgentToolPreflight(AgentToolRegistry registry) {
        this(registry, new ObjectMapper());
    }

    public AgentToolPreflight(AgentToolRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    public PreflightResult prepare(AgentModelToolCall call,
                                   AgentWorkingState state,
                                   String sessionId,
                                   Set<String> permissions,
                                   int sourceOrder) {
        AgentTool<?, ?> tool = registry.find(call.getName()).orElse(null);
        if (tool == null) {
            return blocked(call, "UNKNOWN_TOOL", "unknown tool: " + call.getName());
        }
        AgentToolDescriptor descriptor = tool.descriptor();

        JsonNode args;
        try {
            args = mapper.readTree(call.getArguments());
        } catch (JsonProcessingException e) {
            return blocked(call, "TOOL_ARGUMENT_INVALID", "tool arguments are not valid JSON");
        }
        if (!isValidAgainstSchema(args, tool.inputSchema())) {
            return blocked(call, "TOOL_ARGUMENT_INVALID", "tool arguments failed JSON schema validation");
        }

        if (!descriptor.getAllowedStages().isEmpty()
                && !descriptor.getAllowedStages().contains(state.getStage())) {
            return blocked(call, "TOOL_NOT_ALLOWED_IN_STAGE",
                    "tool " + call.getName() + " is not allowed in stage " + state.getStage());
        }

        if (descriptor.getRequiredPermission() != null
                && !descriptor.getRequiredPermission().isBlank()
                && (permissions == null || !permissions.contains(descriptor.getRequiredPermission()))) {
            return blocked(call, "TOOL_PERMISSION_DENIED",
                    "missing permission: " + descriptor.getRequiredPermission());
        }

        List<String> sensitive = SensitiveValueGuard.findSensitivePaths(args);
        if (!sensitive.isEmpty()) {
            return blocked(call, "SECRET_IN_MODEL_ARGUMENTS",
                    "arguments contain forbidden fields: " + sensitive);
        }

        JsonNode canonical = CanonicalJsonDigester.canonicalize(args);
        String argsDigest = CanonicalJsonDigester.digest(args);
        String idempotencyKey = descriptor.isIdempotent()
                ? CanonicalJsonDigester.sha256(sessionId + "|" + call.getName() + "|" + argsDigest)
                : null;
        String actionDigest = CanonicalJsonDigester.sha256(
                call.getName() + "|" + argsDigest + "|" + state.getResourceVersions() + "|1");

        Object input = mapper.convertValue(args, tool.inputType());
        PreparedToolCall prepared = PreparedToolCall.builder()
                .modelCall(call)
                .tool(tool)
                .input(input)
                .canonicalArgs(canonical)
                .argsDigest(argsDigest)
                .idempotencyKey(idempotencyKey)
                .actionDigest(actionDigest)
                .build();
        return PreflightResult.builder()
                .status(PreflightStatus.ALLOWED)
                .prepared(prepared)
                .build();
    }

    private boolean isValidAgainstSchema(JsonNode args, JsonNode schema) {
        // WP-05 keeps a structural guard; a full JSON Schema validator is
        // layered in with server-side schemas in WP-07+.
        if (args == null || args.isNull()) {
            return schema == null || schema.path("required").isMissingNode();
        }
        if (args.isObject() && schema != null && schema.has("properties")) {
            for (JsonNode required : schema.path("required")) {
                if (!args.has(required.asText())) {
                    return false;
                }
            }
            if (schema.path("additionalProperties").asBoolean(true) == false) {
                java.util.ArrayList<String> keys = new java.util.ArrayList<>();
                args.fieldNames().forEachRemaining(keys::add);
                for (String key : keys) {
                    if (!schema.path("properties").has(key)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private PreflightResult blocked(AgentModelToolCall call, String errorCode, String message) {
        return PreflightResult.builder()
                .status(PreflightStatus.BLOCKED)
                .errorCode(errorCode)
                .safeMessage(message)
                .build();
    }

}

package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.ActionPlan;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.session.model.PendingApprovalState;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal approval manager for run-style side effects.
 */
public class DefaultApprovalManager implements ApprovalManager {

    private static final String CONFIG_REQUEST_KEY = "configRequest";

    @Override
    public PendingApprovalState create(ActionPlan plan, String prompt) {
        return PendingApprovalState.builder()
                .type("EXECUTE_DIFF")
                .prompt(prompt)
                .commandName(plan.getCommandName())
                .commandArgument(plan.getCommandArgument())
                .userInput(plan.getUserInput())
                .attributes(copyAttributes(plan.getAttributes()))
                .configRequest(configRequest(plan.getAttributes()))
                .createdAt(Instant.now())
                .build();
    }

    @Override
    public boolean isApprove(String input) {
        String normalized = normalize(input);
        return "approve execute".equals(normalized)
                || "/approve execute".equals(normalized);
    }

    @Override
    public boolean isDeny(String input) {
        String normalized = normalize(input);
        return "n".equals(normalized)
                || "no".equals(normalized)
                || "deny".equals(normalized)
                || "/deny".equals(normalized)
                || "cancel".equals(normalized)
                || "stop".equals(normalized)
                || "否".equals(normalized)
                || "取消".equals(normalized)
                || "停止".equals(normalized);
    }

    @Override
    public ActionPlan restore(String sessionId, PendingApprovalState pendingApproval) {
        Map<String, Object> attributes = copyAttributes(pendingApproval.getAttributes());
        if (pendingApproval.getConfigRequest() != null) {
            attributes.put(CONFIG_REQUEST_KEY, pendingApproval.getConfigRequest());
        }
        return ActionPlan.builder()
                .sessionId(sessionId)
                .commandName(pendingApproval.getCommandName())
                .commandArgument(pendingApproval.getCommandArgument())
                .userInput(pendingApproval.getUserInput())
                .attributes(attributes)
                .build();
    }

    private Map<String, Object> copyAttributes(Map<String, Object> attributes) {
        return attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
    }

    private ConfigGenerationRequest configRequest(Map<String, Object> attributes) {
        if (attributes == null) {
            return null;
        }
        Object value = attributes.get(CONFIG_REQUEST_KEY);
        return value instanceof ConfigGenerationRequest ? (ConfigGenerationRequest) value : null;
    }

    private String normalize(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }
}

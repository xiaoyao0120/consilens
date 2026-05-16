package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.ActionPlan;
import com.consilens.ai.conversation.engine.model.ActionType;
import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.conversation.engine.model.PlannerResult;
import com.consilens.ai.conversation.engine.model.PlannerRoute;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.runtime.intent.CompareIntentHintExtractor;
import com.consilens.ai.session.model.AiSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds runtime action plans from structured planner output.
 */
public class PlanContextAssembler {

    public ActionPlan toActionPlan(PlannerResult result, PlannerContext context, AiSession session) {
        ActionPlan.ActionPlanBuilder builder = ActionPlan.builder()
                .sessionId(session.getSessionId())
                .commandName(commandName(result.getRoute()))
                .commandArgument(result.getNormalizedGoal())
                .userInput(context == null ? null : context.getRawInput())
                .actionType(actionType(result.getRoute()))
                .attribute(PlannerRuntimeContextKeys.PLANNER_RESULT, result)
                .attribute(PlannerRuntimeContextKeys.PLANNER_ROUTE, result.getRoute() == null ? null : result.getRoute().name());
        if (result.getAssumptions() != null && !result.getAssumptions().isEmpty()) {
            builder.attribute(PlannerRuntimeContextKeys.PLANNER_ASSUMPTIONS, result.getAssumptions());
        }
        if (result.getMissingSlots() != null && !result.getMissingSlots().isEmpty()) {
            builder.attribute(PlannerRuntimeContextKeys.PLANNER_MISSING_SLOTS, result.getMissingSlots());
        }
        if (needsConfigRequest(result.getRoute())) {
            builder.attribute(PlannerRuntimeContextKeys.CONFIG_REQUEST, toConfigRequest(result, context, session));
        }
        return builder.build();
    }

    public ConfigGenerationRequest toConfigRequest(PlannerResult result, PlannerContext context, AiSession session) {
        List<String> hints = new ArrayList<>();
        Map<String, Object> slots = result.getExtractedSlots();
        if (slots != null) {
            slots.forEach((key, value) -> addHint(hints, key, value));
        }
        if (context != null && context.getAttributes() != null) {
            context.getAttributes().forEach((key, value) -> {
                if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                    addHint(hints, key, value);
                }
            });
        }
        return CompareIntentHintExtractor.enrich(
                session.getSessionId(),
                result.getNormalizedGoal() == null ? context.getRawInput() : result.getNormalizedGoal(),
                hints);
    }

    private void addHint(List<String> hints, String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        if (value instanceof Iterable<?>) {
            List<String> parts = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                if (item != null) {
                    parts.add(String.valueOf(item));
                }
            }
            if (!parts.isEmpty()) {
                hints.add(key + "=" + String.join(",", parts));
            }
            return;
        }
        hints.add(key + "=" + value);
    }

    private boolean needsConfigRequest(PlannerRoute route) {
        return route == PlannerRoute.PLAN_CONFIG
                || route == PlannerRoute.MODIFY_CONFIG
                || route == PlannerRoute.RUN_DIFF;
    }

    private String commandName(PlannerRoute route) {
        if (route == null) {
            return "doctor";
        }
        switch (route) {
            case PLAN_CONFIG:
            case MODIFY_CONFIG:
                return "plan";
            case RUN_DIFF:
                return "run";
            case DIAGNOSE:
                return "diagnose";
            case REPAIR:
                return "repair";
            case CHAT:
            default:
                return "doctor";
        }
    }

    private ActionType actionType(PlannerRoute route) {
        if (route == null) {
            return ActionType.GENERAL_RESPONSE;
        }
        switch (route) {
            case PLAN_CONFIG:
            case MODIFY_CONFIG:
                return ActionType.PLAN_CONFIG;
            case RUN_DIFF:
                return ActionType.RUN_DIFF;
            case DIAGNOSE:
                return ActionType.DIAGNOSE_RESULT;
            case REPAIR:
                return ActionType.REPAIR_CONFIG;
            case CHAT:
            default:
                return ActionType.GENERAL_RESPONSE;
        }
    }
}

package com.consilens.server.application.ai.plan;

import com.consilens.agent.api.plan.AgentPlanAction;
import com.consilens.agent.api.plan.AgentPlanActionStatus;
import com.consilens.agent.api.plan.AgentPlanActionType;
import com.consilens.agent.api.plan.AgentPlanStatus;
import com.consilens.agent.api.plan.AgentProvisioningPlan;
import com.consilens.agent.api.state.AgentDatasourceDraftState;
import com.consilens.agent.api.state.AgentProbeStatus;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.core.security.CanonicalJsonDigester;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Freezes the working state into a provisioning plan: two datasource actions
 * (CREATE or explicit REUSE) plus one task definition action. The plan digest
 * binds the approval; any later parameter change invalidates it.
 */
public class ProvisioningPlanCompiler {

    private static final String POLICY_VERSION = "v1";
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentProvisioningPlan compile(AgentWorkingState state) {
        String planId = "plan_" + UUID.randomUUID();
        List<AgentPlanAction> actions = new ArrayList<>();
        int sequence = 1;

        AgentDatasourceDraftState source = requireDraft(state.getSource(), "source");
        AgentDatasourceDraftState target = requireDraft(state.getTarget(), "target");
        if (state.getComparison() == null || state.getComparison().getKeys() == null
                || state.getComparison().getKeys().isEmpty()) {
            throw new IllegalArgumentException("comparison keys are required before plan preparation");
        }
        if (state.getTask() == null || state.getTask().getName() == null
                || state.getTask().getName().isBlank()) {
            throw new IllegalArgumentException("task name is required before plan preparation");
        }

        actions.add(datasourceAction(sequence++, source, "source"));
        actions.add(datasourceAction(sequence++, target, "target"));
        actions.add(taskAction(sequence, state, source, target));

        String configDigest = CanonicalJsonDigester.sha256(
                CanonicalJsonDigester.canonical(mapper.valueToTree(state.getComparison())));
        String planDigest = CanonicalJsonDigester.sha256(
                CanonicalJsonDigester.canonical(mapper.valueToTree(actions))
                        + "|" + configDigest + "|" + POLICY_VERSION);
        return AgentProvisioningPlan.builder()
                .planId(planId)
                .sessionId("")
                .objectiveId(state.getObjectiveId())
                .status(AgentPlanStatus.DRAFT)
                .planDigest(planDigest)
                .configTemplateDigest(configDigest)
                .safeSummary(safeSummary(actions))
                .actions(actions)
                .version(0)
                .createdAt(Instant.now())
                .build();
    }

    private static String safeSummary(List<AgentPlanAction> actions) {
        long createCount = actions.stream()
                .filter(a -> a.getActionType() == AgentPlanActionType.CREATE_DATASOURCE).count();
        long reuseCount = actions.stream()
                .filter(a -> a.getActionType() == AgentPlanActionType.REUSE_DATASOURCE).count();
        StringBuilder summary = new StringBuilder();
        if (createCount > 0) {
            summary.append("创建 ").append(createCount).append(" 个数据源");
        }
        if (reuseCount > 0) {
            if (summary.length() > 0) {
                summary.append("，");
            }
            summary.append("复用 ").append(reuseCount).append(" 个数据源");
        }
        summary.append("和 1 个任务定义");
        return summary.toString();
    }

    /** Recomputed digest for approval binding; any frozen change invalidates it. */
    public String recomputeDigest(AgentProvisioningPlan plan) {
        return CanonicalJsonDigester.sha256(
                CanonicalJsonDigester.canonical(mapper.valueToTree(plan.getActions()))
                        + "|" + plan.getConfigTemplateDigest() + "|" + POLICY_VERSION);
    }

    private static AgentDatasourceDraftState requireDraft(AgentDatasourceDraftState draft, String side) {
        if (draft == null || draft.getProbeStatus() != AgentProbeStatus.SUCCEEDED) {
            throw new IllegalArgumentException(side + " draft must be probed successfully first");
        }
        return draft;
    }

    private AgentPlanAction datasourceAction(int sequence,
                                             AgentDatasourceDraftState draft,
                                             String side) {
        AgentPlanActionType type = draft.getDatasourceId() != null
                ? AgentPlanActionType.REUSE_DATASOURCE : AgentPlanActionType.CREATE_DATASOURCE;
        ObjectNode safeArgs = mapper.createObjectNode();
        safeArgs.put("side", side);
        safeArgs.put("draftId", draft.getDraftId());
        safeArgs.put("name", draft.getName());
        safeArgs.put("type", draft.getType());
        if (draft.getDatasourceId() != null) {
            safeArgs.put("datasourceId", draft.getDatasourceId());
        }
        if (draft.getSecretRequestId() != null) {
            safeArgs.put("secretRequestId", draft.getSecretRequestId());
        }
        safeArgs.put("paramDigest", draft.getParamDigest() == null ? "" : draft.getParamDigest());
        Map<String, Object> frozenParams = new java.util.LinkedHashMap<>();
        if (draft.getNonSecretParams() != null) {
            for (Map.Entry<String, com.consilens.agent.api.state.AgentSlotValue> entry
                    : draft.getNonSecretParams().entrySet()) {
                frozenParams.put(entry.getKey(), entry.getValue().getValue());
            }
        }
        safeArgs.set("nonSecretParams", mapper.valueToTree(frozenParams));
        return AgentPlanAction.builder()
                .actionId("action_" + sequence)
                .sequence(sequence)
                .actionType(type)
                .name(draft.getName())
                .safeArgs(mapper.convertValue(safeArgs, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                }))
                .status(AgentPlanActionStatus.PENDING)
                .build();
    }

    private AgentPlanAction taskAction(int sequence,
                                       AgentWorkingState state,
                                       AgentDatasourceDraftState source,
                                       AgentDatasourceDraftState target) {
        ObjectNode safeArgs = mapper.createObjectNode();
        safeArgs.put("name", state.getTask().getName());
        if (state.getTask().getDescription() != null) {
            safeArgs.put("description", state.getTask().getDescription());
        }
        safeArgs.put("sourceDraftId", source.getDraftId());
        safeArgs.put("targetDraftId", target.getDraftId());
        safeArgs.put("sourceDatabase", source.getDatabase() == null ? "" : source.getDatabase());
        safeArgs.put("targetDatabase", target.getDatabase() == null ? "" : target.getDatabase());
        safeArgs.put("sourceTable", source.getTable() == null ? "" : source.getTable());
        safeArgs.put("targetTable", target.getTable() == null ? "" : target.getTable());
        safeArgs.set("keys", mapper.valueToTree(state.getComparison().getKeys()));
        safeArgs.set("keyMappings", mapper.valueToTree(state.getComparison().getKeyMappings()));
        safeArgs.set("fieldMappings", mapper.valueToTree(state.getComparison().getFieldMappings()));
        safeArgs.set("ignoreColumns", mapper.valueToTree(state.getComparison().getIgnoreColumns()));
        safeArgs.set("strategyHints", mapper.valueToTree(state.getComparison().getStrategyHints()));
        return AgentPlanAction.builder()
                .actionId("action_" + sequence)
                .sequence(sequence)
                .actionType(AgentPlanActionType.CREATE_TASK_DEFINITION)
                .name(state.getTask().getName())
                .safeArgs(mapper.convertValue(safeArgs, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                }))
                .status(AgentPlanActionStatus.PENDING)
                .build();
    }
}

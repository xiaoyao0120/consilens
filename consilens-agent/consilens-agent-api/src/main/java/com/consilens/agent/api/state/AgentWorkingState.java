package com.consilens.agent.api.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only model-visible source of business facts. Immutable; reducers build
 * new instances through {@link #toBuilder()}. Collections are defensively
 * copied at construction and on every getter.
 */
public final class AgentWorkingState {

    private final int schemaVersion;
    private final String objectiveId;
    private final String objective;
    private final AgentWorkflowStage stage;
    private final AgentDatasourceDraftState source;
    private final AgentDatasourceDraftState target;
    private final AgentComparisonDraft comparison;
    private final AgentTaskDraftState task;
    private final AgentPlanStateRef provisioningPlan;
    private final List<String> confirmedAssumptions;
    private final List<String> unresolvedQuestions;
    private final Map<String, String> resourceVersions;
    private final String lastCompletedStep;

    @JsonCreator
    public AgentWorkingState(
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("objectiveId") String objectiveId,
            @JsonProperty("objective") String objective,
            @JsonProperty("stage") AgentWorkflowStage stage,
            @JsonProperty("source") AgentDatasourceDraftState source,
            @JsonProperty("target") AgentDatasourceDraftState target,
            @JsonProperty("comparison") AgentComparisonDraft comparison,
            @JsonProperty("task") AgentTaskDraftState task,
            @JsonProperty("provisioningPlan") AgentPlanStateRef provisioningPlan,
            @JsonProperty("confirmedAssumptions") List<String> confirmedAssumptions,
            @JsonProperty("unresolvedQuestions") List<String> unresolvedQuestions,
            @JsonProperty("resourceVersions") Map<String, String> resourceVersions,
            @JsonProperty("lastCompletedStep") String lastCompletedStep) {
        this.schemaVersion = schemaVersion;
        this.objectiveId = objectiveId;
        this.objective = objective;
        this.stage = stage == null ? AgentWorkflowStage.DISCOVERY : stage;
        this.source = source;
        this.target = target;
        this.comparison = comparison;
        this.task = task;
        this.provisioningPlan = provisioningPlan;
        this.confirmedAssumptions = copyList(confirmedAssumptions);
        this.unresolvedQuestions = copyList(unresolvedQuestions);
        this.resourceVersions = copyMap(resourceVersions);
        this.lastCompletedStep = lastCompletedStep;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getObjectiveId() {
        return objectiveId;
    }

    public String getObjective() {
        return objective;
    }

    public AgentWorkflowStage getStage() {
        return stage;
    }

    public AgentDatasourceDraftState getSource() {
        return source;
    }

    public AgentDatasourceDraftState getTarget() {
        return target;
    }

    public AgentComparisonDraft getComparison() {
        return comparison;
    }

    public AgentTaskDraftState getTask() {
        return task;
    }

    public AgentPlanStateRef getProvisioningPlan() {
        return provisioningPlan;
    }

    public List<String> getConfirmedAssumptions() {
        return Collections.unmodifiableList(confirmedAssumptions);
    }

    public List<String> getUnresolvedQuestions() {
        return Collections.unmodifiableList(unresolvedQuestions);
    }

    public Map<String, String> getResourceVersions() {
        return Collections.unmodifiableMap(resourceVersions);
    }

    public String getLastCompletedStep() {
        return lastCompletedStep;
    }

    public AgentWorkingState withStage(AgentWorkflowStage nextStage) {
        return toBuilder().stage(nextStage).build();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static List<String> copyList(List<String> values) {
        return values == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static Map<String, String> copyMap(Map<String, String> values) {
        return values == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static final class Builder {
        private int schemaVersion = 1;
        private String objectiveId;
        private String objective;
        private AgentWorkflowStage stage = AgentWorkflowStage.DISCOVERY;
        private AgentDatasourceDraftState source;
        private AgentDatasourceDraftState target;
        private AgentComparisonDraft comparison;
        private AgentTaskDraftState task;
        private AgentPlanStateRef provisioningPlan;
        private List<String> confirmedAssumptions = new ArrayList<>();
        private List<String> unresolvedQuestions = new ArrayList<>();
        private Map<String, String> resourceVersions = new LinkedHashMap<>();
        private String lastCompletedStep;

        public Builder() {
        }

        private Builder(AgentWorkingState state) {
            this.schemaVersion = state.schemaVersion;
            this.objectiveId = state.objectiveId;
            this.objective = state.objective;
            this.stage = state.stage;
            this.source = state.source;
            this.target = state.target;
            this.comparison = state.comparison;
            this.task = state.task;
            this.provisioningPlan = state.provisioningPlan;
            this.confirmedAssumptions = new ArrayList<>(state.confirmedAssumptions);
            this.unresolvedQuestions = new ArrayList<>(state.unresolvedQuestions);
            this.resourceVersions = new LinkedHashMap<>(state.resourceVersions);
            this.lastCompletedStep = state.lastCompletedStep;
        }

        public Builder schemaVersion(int value) {
            this.schemaVersion = value;
            return this;
        }

        public Builder objectiveId(String value) {
            this.objectiveId = value;
            return this;
        }

        public Builder objective(String value) {
            this.objective = value;
            return this;
        }

        public Builder stage(AgentWorkflowStage value) {
            this.stage = value;
            return this;
        }

        public Builder source(AgentDatasourceDraftState value) {
            this.source = value;
            return this;
        }

        public Builder target(AgentDatasourceDraftState value) {
            this.target = value;
            return this;
        }

        public Builder comparison(AgentComparisonDraft value) {
            this.comparison = value;
            return this;
        }

        public Builder task(AgentTaskDraftState value) {
            this.task = value;
            return this;
        }

        public Builder provisioningPlan(AgentPlanStateRef value) {
            this.provisioningPlan = value;
            return this;
        }

        public Builder confirmedAssumptions(List<String> values) {
            this.confirmedAssumptions = values == null ? new ArrayList<>() : new ArrayList<>(values);
            return this;
        }

        public Builder unresolvedQuestions(List<String> values) {
            this.unresolvedQuestions = values == null ? new ArrayList<>() : new ArrayList<>(values);
            return this;
        }

        public Builder resourceVersions(Map<String, String> values) {
            this.resourceVersions = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
            return this;
        }

        public Builder lastCompletedStep(String value) {
            this.lastCompletedStep = value;
            return this;
        }

        public AgentWorkingState build() {
            return new AgentWorkingState(
                    schemaVersion,
                    objectiveId,
                    objective,
                    stage,
                    source,
                    target,
                    comparison,
                    task,
                    provisioningPlan,
                    confirmedAssumptions,
                    unresolvedQuestions,
                    resourceVersions,
                    lastCompletedStep);
        }
    }
}

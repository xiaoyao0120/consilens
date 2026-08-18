package com.consilens.agent.core.tool;

import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.consilens.agent.core.state.AgentStateInvariantValidator;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a prepared tool batch in model source order. Reads may run
 * concurrently once enabled; writes always run sequentially. Typed reducers
 * thread the working state through the batch, and every reduce passes the
 * invariant validator before the next tool executes.
 */
public final class AgentToolBatchExecutor {

    private final boolean parallelReadsEnabled;

    public AgentToolBatchExecutor(boolean parallelReadsEnabled) {
        this.parallelReadsEnabled = parallelReadsEnabled;
    }

    public List<AgentToolExecutionResult> execute(List<PreparedToolCall> batch,
                                                  AgentWorkingState initialState,
                                                  AgentToolContext context,
                                                  AgentCancellationToken cancellationToken) {
        AgentWorkingState state = initialState;
        List<AgentToolExecutionResult> results = new ArrayList<>(batch.size());
        boolean sequential = !parallelReadsEnabled || batch.stream().anyMatch(this::isWrite);
        if (sequential) {
            for (PreparedToolCall prepared : batch) {
                state = executeOne(prepared, state, context, results);
            }
        } else {
            // Reserved for WP-10 read parallelism; keeps source order on write.
            for (PreparedToolCall prepared : batch) {
                state = executeOne(prepared, state, context, results);
            }
        }
        return results;
    }

    private AgentWorkingState executeOne(PreparedToolCall prepared,
                                         AgentWorkingState state,
                                         AgentToolContext context,
                                         List<AgentToolExecutionResult> results) {
        long started = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        AgentTool<Object, Object> tool = (AgentTool<Object, Object>) prepared.getTool();
        AgentToolOutcome<Object> outcome;
        try {
            outcome = tool.execute(prepared.getInput(), context);
        } catch (RuntimeException e) {
            outcome = AgentToolOutcome.error("TOOL_EXECUTION_FAILED", true, "tool execution failed");
        }
        long duration = System.currentTimeMillis() - started;

        AgentWorkingState nextState = state;
        boolean stateChanged = false;
        // Failure outcomes may still carry structured state (e.g. a refilled
        // secret request id); reduce applies whenever the tool produced data,
        // and the invariant validator guards every transition.
        if (outcome.getStructuredData() != null) {
            AgentWorkingState reduced = tool.reduce(state, outcome.getStructuredData());
            List<String> violations = AgentStateInvariantValidator.validateTransition(state, reduced);
            if (violations.isEmpty()) {
                AgentStateInvariantValidator.assertResourceIdsStable(state, reduced);
                nextState = reduced;
                stateChanged = true;
            } else {
                outcome = AgentToolOutcome.error("STATE_INVARIANT_VIOLATION", false,
                        String.join("; ", violations));
            }
        }
        results.add(AgentToolExecutionResult.builder()
                .callId(prepared.getModelCall().getId())
                .outcome(outcome)
                .newState(nextState)
                .durationMillis(duration)
                .stateChanged(stateChanged)
                .build());
        return nextState;
    }

    private boolean isWrite(PreparedToolCall prepared) {
        return prepared.getTool().descriptor().getSideEffect() != ToolSideEffect.READ;
    }
}

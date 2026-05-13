package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.ExplainReport;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiSessionStore;

/**
 * Explains the current config artifact in a session.
 */
public class ExplainTask extends AbstractAiTask implements AiTask {

    private final ConfigCapability configCapability;

    public ExplainTask(ConfigCapability configCapability, AiSessionStore sessionStore, AiArtifactStore artifactStore) {
        super(sessionStore, artifactStore);
        this.configCapability = configCapability;
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.EXPLAIN;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        ConfigRef configRef = loadCurrentConfig(context.getSession()).orElse(null);
        if (configRef == null) {
            return failure(type(), "No current config artifact found for explain.");
        }
        ExplainReport report = configCapability.explain(configRef);
        writeOutput(outputPath(context), report.getMarkdown());
        return AiTaskResult.builder()
                .success(true)
                .taskType(type())
                .status(AiTurnResult.Status.COMPLETED)
                .summary(report.getMarkdown())
                .suggestedNextAction("run")
                .build();
    }
}

package com.consilens.cli.ai.runtime;

import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Produces a repair plan artifact from the latest diagnosis.
 */
public class RepairTask extends AbstractAiTask implements AiTask {

    public RepairTask(AiSessionStore sessionStore, AiArtifactStore artifactStore) {
        super(sessionStore, artifactStore);
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.REPAIR;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        ArtifactRef diagnosisArtifact = artifactStore.latest(context.getSession().getSessionId(), ArtifactType.DIAGNOSIS).orElse(null);
        if (diagnosisArtifact == null) {
            return failure(type(), "No diagnosis artifact found for repair.");
        }
        String diagnosis = artifactStore.read(diagnosisArtifact.getArtifactId())
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElse("Diagnosis artifact content unavailable.");
        StringBuilder patch = new StringBuilder()
                .append("# AI Repair Plan").append(System.lineSeparator()).append(System.lineSeparator())
                .append("Session: ").append(context.getSession().getSessionId()).append(System.lineSeparator())
                .append("Diagnosis: ").append(diagnosisArtifact.getArtifactId()).append(System.lineSeparator());
        if (context.getSession().getCurrentConfigArtifactId() != null) {
            patch.append("Current Config: ").append(context.getSession().getCurrentConfigArtifactId()).append(System.lineSeparator());
        }
        patch.append(System.lineSeparator())
                .append("Recommended repair loop:").append(System.lineSeparator())
                .append("1. Review the diagnosis patterns and repair hints below.").append(System.lineSeparator())
                .append("2. Adjust keys, fields, normalization, or credential placeholders in the config.").append(System.lineSeparator())
                .append("3. Re-run `consilens ai run --session ").append(context.getSession().getSessionId())
                .append(" --approve-execute` to verify the fix.").append(System.lineSeparator())
                .append(System.lineSeparator())
                .append(diagnosis);
        ArtifactRef patchArtifact = writeArtifact(
                context.getSession().getSessionId(),
                ArtifactType.REPAIR_PATCH,
                patch.toString(),
                Map.of("task", "repair", "diagnosisArtifactId", diagnosisArtifact.getArtifactId()));
        writeOutput(outputPath(context), patch.toString());
        updateSession(context.getSession(), builder -> builder.currentTask("repair").status("repair_ready"));
        return AiTaskResult.builder()
                .success(true)
                .taskType(type())
                .status(AiTurnResult.Status.COMPLETED)
                .summary("Created repair plan " + patchArtifact.getArtifactId() + System.lineSeparator() + patch)
                .suggestedNextAction("run")
                .build();
    }
}

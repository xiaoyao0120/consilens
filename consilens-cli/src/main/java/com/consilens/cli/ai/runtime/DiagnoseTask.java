package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.DiagnoseCapability;
import com.consilens.ai.execution.model.DiagnoseReport;
import com.consilens.ai.execution.model.EvidenceRef;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;

import java.util.Map;

/**
 * Diagnoses the latest diff evidence for a session.
 */
public class DiagnoseTask extends AbstractAiTask implements AiTask {

    private final DiagnoseCapability diagnoseCapability;

    public DiagnoseTask(DiagnoseCapability diagnoseCapability, AiSessionStore sessionStore, AiArtifactStore artifactStore) {
        super(sessionStore, artifactStore);
        this.diagnoseCapability = diagnoseCapability;
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.DIAGNOSE;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        EvidenceRef evidenceRef = resolveEvidence(context);
        if (evidenceRef == null) {
            return failure(type(), "No diff evidence found for diagnose.");
        }
        DiagnoseReport report = diagnoseCapability.diagnose(evidenceRef);
        String markdown = toMarkdown(report);
        ArtifactRef diagnosisArtifact = writeArtifact(
                context.getSession().getSessionId(),
                ArtifactType.DIAGNOSIS,
                markdown,
                Map.of("task", "diagnose", "evidenceArtifactId", evidenceRef.getArtifactId() == null ? "" : evidenceRef.getArtifactId()));
        writeOutput(outputPath(context), markdown);
        updateSession(context.getSession(), builder -> builder
                .currentTask("diagnose")
                .status("diagnosed")
                .latestDiagnosisArtifactId(diagnosisArtifact.getArtifactId()));
        return AiTaskResult.builder()
                .success(true)
                .taskType(type())
                .status(AiTurnResult.Status.COMPLETED)
                .summary("Diagnosed latest diff evidence for session " + context.getSession().getSessionId()
                        + " diagnosis=" + diagnosisArtifact.getArtifactId()
                        + System.lineSeparator() + markdown)
                .suggestedNextAction("repair")
                .build();
    }

    private EvidenceRef resolveEvidence(AiTaskContext context) {
        String explicitPath = context.attribute(AiRuntimeContextKeys.EVIDENCE_PATH, String.class);
        if (explicitPath != null && !explicitPath.isBlank()) {
            return EvidenceRef.builder()
                    .sessionId(context.getSession().getSessionId())
                    .path(explicitPath.trim())
                    .build();
        }
        return artifactStore.latest(context.getSession().getSessionId(), ArtifactType.DIFF_EVIDENCE)
                .map(ref -> EvidenceRef.builder()
                        .sessionId(ref.getSessionId())
                        .artifactId(ref.getArtifactId())
                        .path(ref.getPath())
                        .build())
                .orElse(null);
    }

    private String toMarkdown(DiagnoseReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# AI Diagnose").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Summary: ").append(report.getSummary()).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Patterns:").append(System.lineSeparator());
        if (report.getPatterns() == null || report.getPatterns().isEmpty()) {
            builder.append("- none").append(System.lineSeparator());
        } else {
            report.getPatterns().forEach(pattern -> builder.append("- ").append(pattern).append(System.lineSeparator()));
        }
        builder.append(System.lineSeparator()).append("Repair Hints:").append(System.lineSeparator());
        if (report.getRepairHints() == null || report.getRepairHints().isEmpty()) {
            builder.append("- none").append(System.lineSeparator());
        } else {
            report.getRepairHints().forEach(hint -> builder.append("- ").append(hint).append(System.lineSeparator()));
        }
        return builder.toString();
    }
}

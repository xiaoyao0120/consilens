package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.DryRunReport;
import com.consilens.ai.execution.model.GeneratedConfig;
import com.consilens.ai.execution.model.ValidationReport;
import com.consilens.ai.runtime.intent.CompareIntentHintExtractor;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskEvent;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiSession;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates and validates a canonical config artifact for a session.
 */
public class PlanConfigTask extends AbstractAiTask implements AiTask {

    private final ConfigCapability configCapability;

    public PlanConfigTask(ConfigCapability configCapability, AiSessionStore sessionStore, AiArtifactStore artifactStore) {
        this(configCapability, sessionStore, artifactStore, null);
    }

    public PlanConfigTask(ConfigCapability configCapability,
                          AiSessionStore sessionStore,
                          AiArtifactStore artifactStore,
                          AiMemoryStore memoryStore) {
        super(sessionStore, artifactStore, memoryStore);
        this.configCapability = configCapability;
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.PLAN_CONFIG;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        AiSession session = context.getSession();
        List<AiTaskEvent> events = new ArrayList<>();
        ConfigGenerationRequest originalRequest = configRequest(context)
                .orElseGet(() -> buildRequestFromInput(context));
        if (originalRequest == null) {
            return failure(type(), "Config generation input is required for plan.",
                    List.of(event("generate-config", "failed", "Config generation input is required for plan.")));
        }
        String originalGoal = originalRequest.getGoal();
        ConfigGenerationRequest request = enrichWithMemories(originalRequest, session.getSessionId());

        GeneratedConfig generated = configCapability.generate(request);
        ArtifactRef configArtifact = writeArtifact(
                session.getSessionId(),
                ArtifactType.CONFIG,
                generated.getConfigRef().getContent(),
                Map.of("task", "plan", "goal", originalGoal == null ? "" : originalGoal));
        events.add(event("generate-config", "completed", "Generated config artifact " + configArtifact.getArtifactId(), configArtifact));

        ConfigRef configRef = ConfigRef.builder()
                .sessionId(session.getSessionId())
                .artifactId(configArtifact.getArtifactId())
                .path(configArtifact.getPath())
                .content(generated.getConfigRef().getContent())
                .build();

        ValidationReport validation = configCapability.validate(configRef);
        ArtifactRef validationArtifact = writeArtifact(session.getSessionId(), ArtifactType.VALIDATION, joinLines(validation.getMessages()),
                Map.of("task", "plan", "passed", String.valueOf(validation.isPassed()),
                        "configArtifactId", configArtifact.getArtifactId()));
        events.add(event("validate", validation.isPassed() ? "completed" : "failed",
                joinLines(validation.getMessages()), validationArtifact));

        DryRunReport dryRun = null;
        ArtifactRef dryRunArtifact = null;
        if (performDryRun(context)) {
            dryRun = configCapability.dryRun(configRef);
            dryRunArtifact = writeArtifact(session.getSessionId(), ArtifactType.DRY_RUN, joinLines(dryRun.getMessages()),
                    Map.of("task", "plan", "passed", String.valueOf(dryRun.isPassed()),
                            "configArtifactId", configArtifact.getArtifactId(),
                            "validationArtifactId", validationArtifact.getArtifactId()));
            events.add(event("dry-run", dryRun.isPassed() ? "completed" : "failed",
                    joinLines(dryRun.getMessages()), dryRunArtifact));
        }

        writeOutput(outputPath(context), generated.getConfigRef().getContent());

        boolean success = validation.isPassed() && (dryRun == null || dryRun.isPassed());

        updateSession(session, builder -> builder
                .currentTask("plan")
                .status(success ? "planned" : "needs_attention")
                .title(originalGoal)
                .currentConfigArtifactId(configArtifact.getArtifactId()));
        remember("goal", originalGoal, "plan:" + session.getSessionId());

        boolean isDraftTemplate = generated.getAssumptions() != null
                && generated.getAssumptions().stream().anyMatch(a -> a.contains("Draft template generated from example"));

        StringBuilder summary = new StringBuilder();
        if (isDraftTemplate && !validation.isPassed()) {
            summary.append("📋 已基于示例模板为您生成配置草稿，请补充以下信息后使用 /validate 验证：\n");
            summary.append(generated.getConfigRef().getContent()).append("\n");
            summary.append("---\n");
            summary.append("💡 提示：\n");
            summary.append("  • 将 connection.url 替换为真实的 JDBC URL，例如 jdbc:mysql://host:3306/db\n");
            summary.append("  • 设置环境变量 SOURCE_USERNAME / SOURCE_PASSWORD / TARGET_USERNAME / TARGET_PASSWORD\n");
            summary.append("  • 或使用 /use-config <path> 加载已有配置\n");
        } else if (outputPath(context) != null && !outputPath(context).isBlank()) {
            // Written to a file — just show the path and status
            summary.append("Config saved to ").append(outputPath(context));
            if (!validation.getMessages().isEmpty()) {
                summary.append(System.lineSeparator()).append("Validation: ").append(joinLines(validation.getMessages()));
            }
        } else {
            // Interactive / inline: always show the generated YAML so the user can see it
            if (success) {
                summary.append("✅ 配置已生成并通过验证：\n");
            } else {
                summary.append("⚠️ 配置已生成但验证有问题，请检查：\n");
            }
            summary.append("```yaml\n");
            summary.append(generated.getConfigRef().getContent().trim());
            summary.append("\n```\n");
            if (!validation.getMessages().isEmpty()) {
                summary.append("Validation: ").append(joinLines(validation.getMessages())).append("\n");
            }
            if (dryRun != null && !dryRun.getMessages().isEmpty()) {
                summary.append("Dry run: ").append(joinLines(dryRun.getMessages())).append("\n");
            }
            if (success) {
                summary.append("\n💡 下一步：\n");
                summary.append("  • /run --approve-execute  执行对比\n");
                summary.append("  • /save <path>  保存配置到文件\n");
                summary.append("  • /validate  重新验证\n");
            }
        }
        return AiTaskResult.builder()
                .success(success)
                .taskType(type())
                .status(success ? AiTurnResult.Status.COMPLETED : AiTurnResult.Status.FAILED)
                .summary(summary.toString())
                .suggestedNextAction(success ? "run" : (isDraftTemplate ? "fill-template" : "plan"))
                .events(events)
                .build();
    }

    private ConfigGenerationRequest buildRequestFromInput(AiTaskContext context) {
        String goal = context.getUserInput();
        if (goal == null || goal.isBlank()) {
            goal = context.getCommand() != null ? context.getCommand().getArgument() : null;
        }
        if (goal == null || goal.isBlank()) {
            return null;
        }
        return CompareIntentHintExtractor.enrich(context.getSession().getSessionId(), goal, null);
    }
}

package com.consilens.cli.command;

import com.consilens.ai.runtime.approval.ApprovalMode;
import com.consilens.ai.runtime.model.AiConsoleCommand;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.orchestrator.AiConversationRuntime;
import com.consilens.cli.ai.runtime.AiRuntimeContextKeys;
import com.consilens.cli.ai.runtime.CliAiRuntimeFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * New AI runtime entrypoint for plan->validate->dry-run->execute->diagnose.
 */
@Command(
    name = "run",
    description = "Execute the AI diff closed loop for a session",
    mixinStandardHelpOptions = true
)
public class AiRunCommand implements Callable<Integer> {

    private final Supplier<AiConversationRuntime> runtimeSupplier;

    @Mixin
    private AIConfigCliOptions options = new AIConfigCliOptions();

    @Option(names = "--session", description = "AI session ID to reuse")
    private String sessionId;

    @Option(names = "--approve-execute", description = "Explicitly approve real diff execution")
    private boolean approveExecute;

    public AiRunCommand() {
        this(() -> new CliAiRuntimeFactory().create());
    }

    AiRunCommand(Supplier<AiConversationRuntime> runtimeSupplier) {
        this.runtimeSupplier = runtimeSupplier;
    }

    @Override
    public Integer call() {
        String effectiveSessionId = AiRuntimeCommandSupport.effectiveSessionId(sessionId);
        AiTaskContext.AiTaskContextBuilder context = AiTaskContext.builder()
                .command(AiConsoleCommand.builder().name("run").argument(options.goal).build())
                .attribute(AiRuntimeContextKeys.APPROVE_EXECUTE, approveExecute)
                .attribute(AiRuntimeContextKeys.APPROVAL_MODE, ApprovalMode.EXPLICIT_FLAG);
        if (options.hasGenerationInput()) {
            context.attribute(AiRuntimeContextKeys.CONFIG_REQUEST, options.toConfigGenerationRequest(effectiveSessionId));
        }
        AiTurnResult result = runtimeSupplier.get().executeCommand(effectiveSessionId, context.build());
        AiRuntimeCommandSupport.print(effectiveSessionId, result);
        return AiRuntimeCommandSupport.exitCode(result);
    }
}

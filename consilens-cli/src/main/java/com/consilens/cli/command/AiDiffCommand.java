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
 * AI diff entrypoint routed through the new runtime.
 */
@Command(
    name = "diff",
    description = "Generate or execute a validated diff flow from AI input",
    mixinStandardHelpOptions = true
)
public class AiDiffCommand implements Callable<Integer> {

    private final Supplier<AiConversationRuntime> runtimeSupplier;

    @Mixin
    private AIConfigCliOptions options = new AIConfigCliOptions();

    @Option(names = {"-o", "--output"}, description = "Output YAML file")
    private String output;

    @Option(names = "--execute", description = "Execute diff after generation through the new AI runtime")
    private boolean execute;

    @Option(names = "--approve-execute", description = "Explicitly approve real diff execution when --execute is used")
    private boolean approveExecute;

    @Option(names = "--session", description = "AI session ID to reuse when --execute is used")
    private String sessionId;

    public AiDiffCommand() {
        this(() -> new CliAiRuntimeFactory().create());
    }

    AiDiffCommand(Supplier<AiConversationRuntime> runtimeSupplier) {
        this.runtimeSupplier = runtimeSupplier;
    }

    @Override
    public Integer call() {
        String effectiveSessionId = AiRuntimeCommandSupport.effectiveSessionId(sessionId);
        if (execute) {
            AiTurnResult result = runtimeSupplier.get().executeCommand(
                    effectiveSessionId,
                    AiTaskContext.builder()
                            .command(AiConsoleCommand.builder().name("run").argument(options.goal).build())
                            .attribute(AiRuntimeContextKeys.CONFIG_REQUEST, options.toConfigGenerationRequest(effectiveSessionId))
                            .attribute(AiRuntimeContextKeys.APPROVE_EXECUTE, approveExecute)
                            .attribute(AiRuntimeContextKeys.APPROVAL_MODE, ApprovalMode.EXPLICIT_FLAG)
                            .build());
            AiRuntimeCommandSupport.print(effectiveSessionId, result);
            return AiRuntimeCommandSupport.exitCode(result);
        }
        if (output == null || output.isBlank()) {
            System.err.println("`--output` is required unless `--execute` is used.");
            return 2;
        }
        AiTurnResult result = runtimeSupplier.get().executeCommand(
                effectiveSessionId,
                AiTaskContext.builder()
                        .command(AiConsoleCommand.builder().name("plan").argument(options.goal).build())
                        .attribute(AiRuntimeContextKeys.CONFIG_REQUEST, options.toConfigGenerationRequest(effectiveSessionId))
                        .attribute(AiRuntimeContextKeys.OUTPUT_PATH, output)
                        .build());
        AiRuntimeCommandSupport.print(effectiveSessionId, result);
        return AiRuntimeCommandSupport.exitCode(result);
    }
}

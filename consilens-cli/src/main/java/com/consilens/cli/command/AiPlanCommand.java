package com.consilens.cli.command;

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
 * New AI runtime entrypoint for planning a config artifact.
 */
@Command(
    name = "plan",
    description = "Generate and validate a session-scoped AI config artifact",
    mixinStandardHelpOptions = true
)
public class AiPlanCommand implements Callable<Integer> {

    private final Supplier<AiConversationRuntime> runtimeSupplier;

    @Mixin
    private AIConfigCliOptions options = new AIConfigCliOptions();

    @Option(names = "--session", description = "AI session ID to reuse")
    private String sessionId;

    @Option(names = {"-o", "--output"}, description = "Also write generated YAML to a file")
    private String output;

    @Option(names = "--dry-run", description = "Run dry-run after planning the config")
    private boolean dryRun;

    public AiPlanCommand() {
        this(() -> new CliAiRuntimeFactory().create());
    }

    AiPlanCommand(Supplier<AiConversationRuntime> runtimeSupplier) {
        this.runtimeSupplier = runtimeSupplier;
    }

    @Override
    public Integer call() {
        String effectiveSessionId = AiRuntimeCommandSupport.effectiveSessionId(sessionId);
        AiTurnResult result = runtimeSupplier.get().executeCommand(
                effectiveSessionId,
                AiTaskContext.builder()
                        .command(AiConsoleCommand.builder().name("plan").argument(options.goal).build())
                        .attribute(AiRuntimeContextKeys.CONFIG_REQUEST, options.toConfigGenerationRequest(effectiveSessionId))
                        .attribute(AiRuntimeContextKeys.OUTPUT_PATH, output)
                        .attribute(AiRuntimeContextKeys.PERFORM_DRY_RUN, dryRun)
                        .build());
        AiRuntimeCommandSupport.print(effectiveSessionId, result);
        return AiRuntimeCommandSupport.exitCode(result);
    }
}

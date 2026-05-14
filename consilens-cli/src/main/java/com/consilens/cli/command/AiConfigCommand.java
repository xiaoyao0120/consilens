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
 * Generates a production-shaped Consilens YAML configuration with AI assistance.
 */
@Command(
    name = "config",
    description = "Generate a validated Consilens YAML configuration from a goal and explicit hints",
    mixinStandardHelpOptions = true
)
public class AiConfigCommand implements Callable<Integer> {

    private final Supplier<AiConversationRuntime> runtimeSupplier;

    @Mixin
    private AIConfigCliOptions options = new AIConfigCliOptions();

    @Option(names = {"-o", "--output"}, description = "Output YAML file")
    private String output;

    @Option(names = "--dry-run", description = "Run DiffService dry-run after generating the config")
    private boolean dryRun;

    @Option(names = "--session", description = "AI session ID to reuse")
    private String sessionId;

    public AiConfigCommand() {
        this(() -> new CliAiRuntimeFactory().create());
    }

    AiConfigCommand(Supplier<AiConversationRuntime> runtimeSupplier) {
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
                        .attribute(AiRuntimeContextKeys.INLINE_OUTPUT, output == null || output.isBlank())
                        .build());
        AiRuntimeCommandSupport.print(effectiveSessionId, result);
        return AiRuntimeCommandSupport.exitCode(result);
    }
}

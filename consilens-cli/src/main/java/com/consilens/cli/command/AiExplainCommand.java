package com.consilens.cli.command;

import com.consilens.ai.runtime.model.AiConsoleCommand;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.orchestrator.AiConversationRuntime;
import com.consilens.cli.ai.runtime.AiRuntimeContextKeys;
import com.consilens.cli.ai.runtime.CliAiRuntimeFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Explains a Consilens configuration using deterministic engine facts.
 */
@Command(
    name = "explain",
    description = "Explain a Consilens YAML configuration and its execution risks",
    mixinStandardHelpOptions = true
)
public class AiExplainCommand implements Callable<Integer> {

    private final Supplier<AiConversationRuntime> runtimeSupplier;

    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file path")
    private String configFile;

    @Option(names = "--dry-run", description = "Run DiffService dry-run before printing the explanation")
    private boolean dryRun;

    @Option(names = "--session", description = "AI session ID to reuse")
    private String sessionId;

    public AiExplainCommand() {
        this(() -> new CliAiRuntimeFactory().create());
    }

    AiExplainCommand(Supplier<AiConversationRuntime> runtimeSupplier) {
        this.runtimeSupplier = runtimeSupplier;
    }

    @Override
    public Integer call() {
        String effectiveSessionId = AiRuntimeCommandSupport.effectiveSessionId(sessionId);
        AiTurnResult result = runtimeSupplier.get().executeCommand(
                effectiveSessionId,
                AiTaskContext.builder()
                        .command(AiConsoleCommand.builder().name("explain").argument(configFile).build())
                        .attribute(AiRuntimeContextKeys.CONFIG_PATH, configFile)
                        .attribute(AiRuntimeContextKeys.PERFORM_DRY_RUN, dryRun)
                        .attribute(AiRuntimeContextKeys.INLINE_OUTPUT, true)
                        .build());
        AiRuntimeCommandSupport.print(effectiveSessionId, result);
        return AiRuntimeCommandSupport.exitCode(result);
    }
}

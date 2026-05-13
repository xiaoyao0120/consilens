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
 * Produces a repair-plan artifact from the latest diagnosis in a session.
 */
@Command(
    name = "repair",
    description = "Generate a repair plan from the latest session diagnosis",
    mixinStandardHelpOptions = true
)
public class AiRepairCommand implements Callable<Integer> {

    private final Supplier<AiConversationRuntime> runtimeSupplier;

    @Option(names = "--session", required = true, description = "AI session ID to repair")
    private String sessionId;

    @Option(names = {"-o", "--output"}, description = "Write repair plan to a file")
    private String output;

    public AiRepairCommand() {
        this(() -> new CliAiRuntimeFactory().create());
    }

    AiRepairCommand(Supplier<AiConversationRuntime> runtimeSupplier) {
        this.runtimeSupplier = runtimeSupplier;
    }

    @Override
    public Integer call() {
        String effectiveSessionId = AiRuntimeCommandSupport.effectiveSessionId(sessionId);
        AiTurnResult result = runtimeSupplier.get().executeCommand(
                effectiveSessionId,
                AiTaskContext.builder()
                        .command(AiConsoleCommand.builder().name("repair").build())
                        .attribute(AiRuntimeContextKeys.OUTPUT_PATH, output)
                        .build());
        AiRuntimeCommandSupport.print(effectiveSessionId, result);
        return AiRuntimeCommandSupport.exitCode(result);
    }
}

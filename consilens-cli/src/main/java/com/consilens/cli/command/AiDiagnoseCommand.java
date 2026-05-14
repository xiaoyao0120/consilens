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
 * Diagnoses existing diff evidence with deterministic rule-based analysis.
 */
@Command(
    name = "diagnose",
    description = "Diagnose an existing diff result or diff-record JSON file",
    mixinStandardHelpOptions = true
)
public class AiDiagnoseCommand implements Callable<Integer> {

    private static final String DEFAULT_ANALYZER = "rulebased";

    private final Supplier<AiConversationRuntime> runtimeSupplier;

    @Option(names = "--result", required = true, description = "Path to DiffResult JSON or diff-record JSON array")
    private String resultPath;

    @Option(names = "--analyzer", description = "Analyzer provider name. Defaults to CONSILENS_AI_ANALYZER or rulebased")
    private String analyzer;

    @Option(names = {"-o", "--output"}, description = "Write diagnosis report to a file instead of stdout")
    private String output;

    @Option(names = "--session", description = "AI session ID to reuse")
    private String sessionId;

    public AiDiagnoseCommand() {
        this(() -> new CliAiRuntimeFactory().create());
    }

    AiDiagnoseCommand(Supplier<AiConversationRuntime> runtimeSupplier) {
        this.runtimeSupplier = runtimeSupplier;
    }

    @Override
    public Integer call() {
        String effectiveSessionId = AiRuntimeCommandSupport.effectiveSessionId(sessionId);
        AiTurnResult result = runtimeSupplier.get().executeCommand(
                effectiveSessionId,
                AiTaskContext.builder()
                        .command(AiConsoleCommand.builder().name("diagnose").argument(resultPath).build())
                        .attribute(AiRuntimeContextKeys.EVIDENCE_PATH, resultPath)
                        .attribute(AiRuntimeContextKeys.ANALYZER, resolveAnalyzer())
                        .attribute(AiRuntimeContextKeys.OUTPUT_PATH, output)
                        .attribute(AiRuntimeContextKeys.INLINE_OUTPUT, output == null || output.isBlank())
                        .build());
        AiRuntimeCommandSupport.print(effectiveSessionId, result);
        return AiRuntimeCommandSupport.exitCode(result);
    }

    private String resolveAnalyzer() {
        if (analyzer != null && !analyzer.isBlank()) {
            return analyzer.trim();
        }
        String envAnalyzer = System.getenv("CONSILENS_AI_ANALYZER");
        if (envAnalyzer != null && !envAnalyzer.isBlank()) {
            return envAnalyzer.trim();
        }
        return DEFAULT_ANALYZER;
    }
}

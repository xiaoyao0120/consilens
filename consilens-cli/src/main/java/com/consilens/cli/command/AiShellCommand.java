package com.consilens.cli.command;

import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.runtime.approval.ApprovalMode;
import com.consilens.ai.runtime.model.AiConsoleCommand;
import com.consilens.ai.runtime.model.AiSessionSnapshot;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.orchestrator.AiConversationRuntime;
import com.consilens.cli.ai.runtime.AiRuntimeContextKeys;
import com.consilens.cli.ai.runtime.CliAiRuntimeFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Minimal interactive shell over the new AI runtime.
 */
@Command(
    name = "shell",
    description = "Start an interactive AI shell over the new runtime",
    mixinStandardHelpOptions = true
)
public class AiShellCommand implements Callable<Integer> {

    private final Supplier<AiConversationRuntime> runtimeSupplier;

    @Option(names = "--session", description = "AI session ID to reuse")
    private String sessionId;

    public AiShellCommand() {
        this(() -> new CliAiRuntimeFactory().create());
    }

    AiShellCommand(Supplier<AiConversationRuntime> runtimeSupplier) {
        this.runtimeSupplier = runtimeSupplier;
    }

    @Override
    public Integer call() throws Exception {
        String effectiveSessionId = AiRuntimeCommandSupport.effectiveSessionId(sessionId);
        AiConversationRuntime runtime = runtimeSupplier.get();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("AI shell session=" + effectiveSessionId);
        System.out.println("Commands: /plan <goal>, /run [--approve-execute] <goal>, /diagnose [path], /repair, /explain, /snapshot, /exit");
        while (true) {
            System.out.print("consilens ai> ");
            String line = reader.readLine();
            if (line == null || "/exit".equalsIgnoreCase(line.trim()) || "exit".equalsIgnoreCase(line.trim())) {
                return 0;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if ("/snapshot".equalsIgnoreCase(trimmed)) {
                AiSessionSnapshot snapshot = runtime.snapshot(effectiveSessionId);
                System.out.println("session=" + snapshot.getSession().getSessionId()
                        + " config=" + snapshot.getSession().getCurrentConfigArtifactId()
                        + " latestRun=" + snapshot.getLatestArtifactId());
                continue;
            }
            AiTurnResult result = trimmed.startsWith("/")
                    ? runtime.executeCommand(effectiveSessionId, commandContext(effectiveSessionId, trimmed))
                    : runtime.handleUserInput(effectiveSessionId, trimmed);
            AiRuntimeCommandSupport.print(effectiveSessionId, result);
        }
    }

    private AiTaskContext commandContext(String sessionId, String line) {
        if (line.startsWith("/plan ")) {
            String goal = line.substring("/plan ".length()).trim();
            return AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("plan").argument(goal).build())
                    .attribute(AiRuntimeContextKeys.CONFIG_REQUEST, ConfigGenerationRequest.builder()
                            .sessionId(sessionId)
                            .goal(goal)
                            .build())
                    .build();
        }
        if (line.startsWith("/run")) {
            String payload = line.substring("/run".length()).trim();
            boolean approve = payload.startsWith("--approve-execute");
            if (approve) {
                payload = payload.substring("--approve-execute".length()).trim();
            }
            AiTaskContext.AiTaskContextBuilder builder = AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("run").argument(payload).build())
                    .attribute(AiRuntimeContextKeys.APPROVE_EXECUTE, approve)
                    .attribute(AiRuntimeContextKeys.APPROVAL_MODE, ApprovalMode.EXPLICIT_FLAG);
            if (!payload.isBlank()) {
                builder.attribute(AiRuntimeContextKeys.CONFIG_REQUEST, ConfigGenerationRequest.builder()
                        .sessionId(sessionId)
                        .goal(payload)
                        .build());
            }
            return builder.build();
        }
        if (line.startsWith("/diagnose")) {
            String payload = line.substring("/diagnose".length()).trim();
            AiTaskContext.AiTaskContextBuilder builder = AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("diagnose").argument(payload).build());
            if (!payload.isBlank()) {
                builder.attribute(AiRuntimeContextKeys.EVIDENCE_PATH, payload);
            }
            return builder.build();
        }
        if (line.startsWith("/repair")) {
            return AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("repair").build())
                    .build();
        }
        if (line.startsWith("/explain")) {
            return AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("explain").build())
                    .build();
        }
        return AiTaskContext.builder()
                .command(AiConsoleCommand.builder().name("doctor").argument(line).build())
                .build();
    }
}

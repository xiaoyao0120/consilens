package com.consilens.cli.command;

import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.runtime.approval.ApprovalMode;
import com.consilens.ai.runtime.model.AiConsoleCommand;
import com.consilens.ai.runtime.model.AiSessionSnapshot;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.orchestrator.AiConversationRuntime;
import com.consilens.ai.session.model.AiMemory;
import com.consilens.cli.ai.runtime.AiRuntimePaths;
import com.consilens.cli.ai.runtime.AiRuntimeContextKeys;
import com.consilens.cli.ai.runtime.CliAiRuntimeFactory;
import com.consilens.cli.ai.runtime.FileAiMemoryStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
        FileAiMemoryStore memoryStore = new FileAiMemoryStore(new AiRuntimePaths());
        PendingRun pendingRun = null;
        System.out.println("AI shell session=" + effectiveSessionId);
        printHelp();
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
            if ("/help".equalsIgnoreCase(trimmed)) {
                printHelp();
                continue;
            }
            if ("/approve execute".equalsIgnoreCase(trimmed)) {
                if (pendingRun == null) {
                    System.out.println("No pending run approval.");
                    continue;
                }
                AiTurnResult approved = runtime.executeCommand(effectiveSessionId, pendingRun.approvedContext());
                pendingRun = approved.getStatus() == AiTurnResult.Status.REQUIRES_APPROVAL ? pendingRun : null;
                AiRuntimeCommandSupport.print(effectiveSessionId, approved);
                continue;
            }
            if ("/deny".equalsIgnoreCase(trimmed)) {
                pendingRun = null;
                System.out.println("Pending approval cleared.");
                continue;
            }
            if ("/snapshot".equalsIgnoreCase(trimmed)) {
                AiSessionSnapshot snapshot = runtime.snapshot(effectiveSessionId);
                System.out.println("session=" + snapshot.getSession().getSessionId()
                        + " config=" + snapshot.getSession().getCurrentConfigArtifactId()
                        + " latestRun=" + snapshot.getLatestArtifactId());
                continue;
            }
            if ("/memories".equalsIgnoreCase(trimmed)) {
                printMemories(memoryStore.list(), effectiveSessionId);
                continue;
            }
            AiTaskContext commandContext = trimmed.startsWith("/") ? commandContext(effectiveSessionId, trimmed) : null;
            AiTurnResult result = trimmed.startsWith("/")
                    ? runtime.executeCommand(effectiveSessionId, commandContext)
                    : runtime.handleUserInput(effectiveSessionId, trimmed);
            if (result.getStatus() == AiTurnResult.Status.REQUIRES_APPROVAL
                    && commandContext != null
                    && commandContext.getCommand() != null
                    && "run".equalsIgnoreCase(commandContext.getCommand().getName())) {
                pendingRun = new PendingRun(commandContext);
                System.out.println("Pending approval created. Use `/approve execute` or `/deny`.");
            }
            AiRuntimeCommandSupport.print(effectiveSessionId, result);
        }
    }

    private void printHelp() {
        System.out.println("Commands: /plan <goal>, /run [--approve-execute] <goal>, /approve execute, /deny, /diagnose [path], /repair, /explain [path], /snapshot, /memories, /help, /exit");
    }

    private void printMemories(List<AiMemory> memories, String sessionId) {
        List<AiMemory> filtered = memories.stream()
                .filter(memory -> memory.getSource() != null && memory.getSource().contains(sessionId))
                .sorted((left, right) -> createdAt(right).compareTo(createdAt(left)))
                .limit(10)
                .collect(Collectors.toList());
        if (filtered.isEmpty()) {
            System.out.println("No memories stored for session " + sessionId + ".");
            return;
        }
        System.out.println("# Session Memories");
        for (AiMemory memory : filtered) {
            System.out.println("- [" + memory.getType() + "] " + memory.getContent());
            System.out.println("  source=" + memory.getSource());
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
            String payload = line.substring("/explain".length()).trim();
            AiTaskContext.AiTaskContextBuilder builder = AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("explain").argument(payload).build());
            if (!payload.isBlank()) {
                builder.attribute(AiRuntimeContextKeys.CONFIG_PATH, payload);
            }
            return builder.build();
        }
        return AiTaskContext.builder()
                .command(AiConsoleCommand.builder().name("doctor").argument(line).build())
                .build();
    }

    private Instant createdAt(AiMemory memory) {
        return memory == null || memory.getCreatedAt() == null ? Instant.EPOCH : memory.getCreatedAt();
    }

    private static class PendingRun {

        private final AiTaskContext original;

        private PendingRun(AiTaskContext original) {
            this.original = original;
        }

        private AiTaskContext approvedContext() {
            return original.toBuilder()
                    .attribute(AiRuntimeContextKeys.APPROVE_EXECUTE, true)
                    .build();
        }
    }
}

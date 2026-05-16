package com.consilens.cli.command;

import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.runtime.approval.ApprovalMode;
import com.consilens.ai.runtime.model.AiConsoleCommand;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.cli.ai.runtime.AiRuntimeContextKeys;

final class AiConsoleContextParser {

    AiTaskContext parse(String sessionId, String line) {
        if ("/plan".equals(line) || line.startsWith("/plan ")) {
            String goal = line.substring("/plan".length()).trim();
            AiTaskContext.AiTaskContextBuilder builder = AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("plan").argument(goal).build());
            if (!goal.isBlank()) {
                builder.attribute(AiRuntimeContextKeys.CONFIG_REQUEST, ConfigGenerationRequest.builder()
                        .sessionId(sessionId)
                        .goal(goal)
                        .build());
            }
            return builder.build();
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
        if (line.startsWith("/check")) {
            return AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("check").argument("check").build())
                    .attribute(AiRuntimeContextKeys.PERFORM_DRY_RUN, true)
                    .attribute(AiRuntimeContextKeys.INLINE_OUTPUT, true)
                    .build();
        }
        if (line.startsWith("/validate")) {
            String payload = line.substring("/validate".length()).trim();
            AiTaskContext.AiTaskContextBuilder builder = AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("validate").argument(payload).build())
                    .attribute(AiRuntimeContextKeys.INLINE_OUTPUT, true);
            if (!payload.isBlank()) {
                builder.attribute(AiRuntimeContextKeys.CONFIG_PATH, payload);
            }
            return builder.build();
        }
        if (line.startsWith("/dry-run")) {
            String payload = line.substring("/dry-run".length()).trim();
            AiTaskContext.AiTaskContextBuilder builder = AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("dry-run").argument(payload).build())
                    .attribute(AiRuntimeContextKeys.INLINE_OUTPUT, true);
            if (!payload.isBlank()) {
                builder.attribute(AiRuntimeContextKeys.CONFIG_PATH, payload);
            }
            return builder.build();
        }
        if (line.startsWith("/diff")) {
            String payload = line.substring("/diff".length()).trim();
            boolean approve = payload.startsWith("--approve-execute");
            if (approve) {
                payload = payload.substring("--approve-execute".length()).trim();
            }
            return AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("diff").argument(payload).build())
                    .attribute(AiRuntimeContextKeys.APPROVE_EXECUTE, approve)
                    .attribute(AiRuntimeContextKeys.APPROVAL_MODE, ApprovalMode.EXPLICIT_FLAG)
                    .build();
        }
        if (line.startsWith("/analyze-last")) {
            return AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("analyze-last").argument("").build())
                    .attribute(AiRuntimeContextKeys.INLINE_OUTPUT, true)
                    .build();
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
        if (line.startsWith("/remember")) {
            String payload = line.substring("/remember".length()).trim();
            String[] parts = payload.split("\\s+", 2);
            AiTaskContext.AiTaskContextBuilder builder = AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("remember").argument(payload).build());
            if (parts.length > 0 && !parts[0].isBlank()) {
                builder.attribute(AiRuntimeContextKeys.MEMORY_TYPE, parts[0].trim());
            }
            if (parts.length > 1 && !parts[1].isBlank()) {
                builder.attribute(AiRuntimeContextKeys.MEMORY_CONTENT, parts[1].trim());
            }
            return builder.build();
        }
        if (line.startsWith("/use-config")) {
            String payload = line.substring("/use-config".length()).trim();
            return AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("use-config").argument(payload).build())
                    .attribute(AiRuntimeContextKeys.CONFIG_PATH, payload)
                    .build();
        }
        if (line.startsWith("/forget")) {
            String payload = line.substring("/forget".length()).trim();
            return AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("forget").argument(payload).build())
                    .attribute(AiRuntimeContextKeys.MEMORY_ID, payload)
                    .build();
        }
        if (line.startsWith("/explain")) {
            String payload = line.substring("/explain".length()).trim();
            AiTaskContext.AiTaskContextBuilder builder = AiTaskContext.builder()
                    .command(AiConsoleCommand.builder().name("explain").argument(payload).build())
                    .attribute(AiRuntimeContextKeys.INLINE_OUTPUT, true);
            if (!payload.isBlank()) {
                builder.attribute(AiRuntimeContextKeys.CONFIG_PATH, payload);
            }
            return builder.build();
        }
        return null;
    }
}

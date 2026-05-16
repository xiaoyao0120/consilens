package com.consilens.cli.command;

import com.consilens.ai.conversation.api.ConversationService;
import com.consilens.ai.conversation.api.model.ConversationCommandRequest;
import com.consilens.ai.conversation.api.model.ConversationEventDto;
import com.consilens.ai.conversation.api.model.ConversationResponse;
import com.consilens.ai.conversation.api.model.ArtifactContentResponse;
import com.consilens.ai.conversation.api.model.ArtifactEntryDto;
import com.consilens.ai.conversation.api.model.CurrentConfigResponse;
import com.consilens.ai.conversation.api.model.MemoryEntryDto;
import com.consilens.ai.conversation.api.model.NextStepDto;
import com.consilens.ai.conversation.api.model.SessionRecoveryResponse;
import com.consilens.ai.conversation.api.model.SaveConfigResponse;
import com.consilens.ai.conversation.api.model.SessionSnapshot;
import com.consilens.ai.conversation.api.model.SessionSummaryDto;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.cli.ai.runtime.AiRuntimeContextKeys;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiConsoleLoopTest {

    @Test
    void shouldRenderResponseTypeNextStepAndAuditState() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AiConsoleLoop loop = new AiConsoleLoop(() -> new StubConversationService(),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(output));

        int exitCode = loop.start("loop-1", true, "compare orders");

        assertEquals(0, exitCode);
        String rendered = output.toString();
        assertTrue(rendered.contains("[AI MESSAGE] session=loop-1"));
        assertTrue(rendered.contains("Next: run - Continue with execute or repair."));
        assertTrue(rendered.contains("State: task=plan status=ready config=config-1 run=result-1 diagnosis=diag-1 audit=audit-1"));
    }

    @Test
    void shouldPrintStagePreambleForRunCommand() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AiConsoleLoop loop = new AiConsoleLoop(() -> new StubConversationService(),
                new ByteArrayInputStream("/run\n/quit\n".getBytes()),
                new PrintStream(output));

        int exitCode = loop.start("loop-2", true, null);

        assertEquals(0, exitCode);
        assertTrue(output.toString().contains("[AI STAGE] validate -> dry-run -> approval -> diff -> analyze"));
    }

    @Test
    void shouldRenderRuntimeEventsAndArtifacts() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AiConsoleLoop loop = new AiConsoleLoop(() -> new StubConversationService(),
                new ByteArrayInputStream("/artifacts run_audit\n/artifact audit-1\n/quit\n".getBytes()),
                new PrintStream(output));

        int exitCode = loop.start("loop-3", true, null);

        assertEquals(0, exitCode);
        String rendered = output.toString();
        assertTrue(rendered.contains("# Session Artifacts (run_audit)"));
        assertTrue(rendered.contains("metadata={resultArtifactId=result-1}"));
        assertTrue(rendered.contains("# Artifact audit-1 [RUN_AUDIT]"));
        assertTrue(rendered.contains("sha256=abc123"));
    }

    @Test
    void shouldRenderRecoverySummary() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AiConsoleLoop loop = new AiConsoleLoop(() -> new StubConversationService(),
                new ByteArrayInputStream("/recover\n/quit\n".getBytes()),
                new PrintStream(output));

        int exitCode = loop.start("loop-4", true, null);

        assertEquals(0, exitCode);
        assertTrue(output.toString().contains("# Session Recovery"));
        assertTrue(output.toString().contains("Recommended action: repair"));
    }

    @Test
    void shouldFormatWelcomeScreenInsteadOfInlineSummaryAndFullCommandDump() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AiConsoleLoop loop = new AiConsoleLoop(() -> new WelcomeSummaryConversationService(),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(output));

        int exitCode = loop.start("loop-4b", true, null);

        assertEquals(0, exitCode);
        String rendered = output.toString();
        assertTrue(rendered.contains("Session: loop-4b"));
        assertTrue(!rendered.contains("summary="));
        assertTrue(rendered.contains("Last result:"));
        assertTrue(rendered.contains("Quick start:"));
        assertTrue(!rendered.contains("Commands: /plan"));
    }

    @Test
    void shouldBootstrapStartupConfigBeforeEnteringLoop() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CapturingConversationService service = new CapturingConversationService();
        AiConsoleLoop loop = new AiConsoleLoop(() -> service,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(output),
                AiConsoleStartupOptions.builder()
                        .configPath("/tmp/orders.yaml")
                        .build());

        int exitCode = loop.start("loop-5", true, "compare orders");

        assertEquals(0, exitCode);
        assertEquals(List.of("use-config"), service.commandNames);
        assertEquals("/tmp/orders.yaml", service.commandRequests.get(0).getAttributes().get(AiRuntimeContextKeys.CONFIG_PATH));
    }

    @Test
    void shouldKeepStartupApiKeyOutOfOutput() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CapturingConversationService service = new CapturingConversationService(false);
        AiConsoleLoop loop = new AiConsoleLoop(() -> service,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(output),
                AiConsoleStartupOptions.builder()
                        .backend("openai")
                        .apiKey("top-secret")
                        .build());

        int exitCode = loop.start("loop-6", true, "compare orders");

        assertEquals(0, exitCode);
        assertTrue(service.commandNames.isEmpty());
        assertEquals(List.of("compare orders"), service.turnInputs);
        assertTrue(!output.toString().contains("top-secret"));
        assertTrue(!output.toString().contains("has no resolved API key"));
    }

    @Test
    void shouldPlanWhenNaturalInputAlreadyContainsEnoughStructure() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CapturingConversationService service = new CapturingConversationService(false);
        AiConsoleLoop loop = new AiConsoleLoop(() -> service,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(output),
                AiConsoleStartupOptions.builder()
                        .backend("openai")
                        .apiKey("top-secret")
                        .build());

        int exitCode = loop.start("loop-6b", true, "compare mysql orders -> postgresql orders keys order_id");

        assertEquals(0, exitCode);
        assertEquals(List.of("compare mysql orders -> postgresql orders keys order_id"), service.turnInputs);
        assertTrue("top-secret".equals(service.turnAttributes.get(0).get("apiKey")));
    }

    @Test
    void shouldInferCompareHintsFromChineseNaturalInput() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CapturingConversationService service = new CapturingConversationService(false);
        AiConsoleLoop loop = new AiConsoleLoop(() -> service,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(output),
                AiConsoleStartupOptions.builder()
                        .backend("openai")
                        .apiKey("top-secret")
                        .build());

        int exitCode = loop.start("loop-6c", true, "我想比较 mysql 和 postgresql 中 users 表的数据");

        assertEquals(0, exitCode);
        assertEquals(List.of("我想比较 mysql 和 postgresql 中 users 表的数据"), service.turnInputs);
        assertTrue("openai".equals(service.turnAttributes.get(0).get("backend")));
    }

    @Test
    void shouldInferCompareHintsFromChineseBiDuiNaturalInput() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CapturingConversationService service = new CapturingConversationService(false);
        AiConsoleLoop loop = new AiConsoleLoop(() -> service,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(output),
                AiConsoleStartupOptions.builder()
                        .backend("openai")
                        .apiKey("top-secret")
                        .build());

        int exitCode = loop.start("loop-6d", true, "我想比对mysql和postgresql的users表数据");

        assertEquals(0, exitCode);
        assertEquals(List.of("我想比对mysql和postgresql的users表数据"), service.turnInputs);
        assertTrue("openai".equals(service.turnAttributes.get(0).get("backend")));
    }

    private static class StubConversationService implements ConversationService {
        @Override
        public SessionSnapshot startSession(String preferredSessionId, boolean fresh) {
            return snapshot(preferredSessionId);
        }

        @Override
        public SessionSnapshot resumeSession(String sessionId) {
            return snapshot(sessionId);
        }

        @Override
        public List<SessionSummaryDto> listSessions(int limit) {
            return List.of();
        }

        @Override
        public ConversationResponse sendUserTurn(String sessionId, String text) {
            return ConversationResponse.builder()
                    .type(ConversationResponse.Type.MESSAGE)
                    .message("planned")
                    .events(List.of(ConversationEventDto.builder()
                            .stage("generate-config")
                            .status("completed")
                            .message("config generated")
                            .artifactId("config-1")
                            .artifactType("CONFIG")
                            .build()))
                    .suggestedNextStep(NextStepDto.builder()
                            .code("run")
                            .description("Continue with execute or repair.")
                            .build())
                    .session(snapshot(sessionId))
                    .build();
        }

        @Override
        public ConversationResponse sendUserTurn(String sessionId, String text, java.util.Map<String, Object> attributes) {
            return sendUserTurn(sessionId, text);
        }

        @Override
        public ConversationResponse executeCommand(ConversationCommandRequest request) {
            return ConversationResponse.builder()
                    .type(ConversationResponse.Type.MESSAGE)
                    .message("command")
                    .session(snapshot(request.getSessionId()))
                    .build();
        }

        @Override
        public ConversationResponse approve(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConversationResponse deny(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionSnapshot getSessionSnapshot(String sessionId) {
            return snapshot(sessionId);
        }

        @Override
        public SessionRecoveryResponse recoverSession(String sessionId) {
            return SessionRecoveryResponse.builder()
                    .session(snapshot(sessionId))
                    .latestAudit(getArtifact("audit-1"))
                    .summary("Recovered session " + sessionId + System.lineSeparator() + "Recommended action: repair")
                    .recommendedAction("repair")
                    .build();
        }

        @Override
        public CurrentConfigResponse getCurrentConfig(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SaveConfigResponse saveCurrentConfig(String sessionId, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<MemoryEntryDto> listMemory(String sessionId, int limit) {
            return List.of();
        }

        @Override
        public List<ArtifactEntryDto> listArtifacts(String sessionId, int limit) {
            return List.of(ArtifactEntryDto.builder()
                    .artifactId("audit-1")
                    .sessionId(sessionId)
                    .type("RUN_AUDIT")
                    .path("/tmp/audit-1.txt")
                    .sha256("abc123")
                    .metadata(java.util.Map.of("resultArtifactId", "result-1"))
                    .createdAt(Instant.now())
                    .build());
        }

        @Override
        public ArtifactContentResponse getArtifact(String artifactId) {
            return ArtifactContentResponse.builder()
                    .found(true)
                    .artifactId(artifactId)
                    .type("RUN_AUDIT")
                    .path("/tmp/audit-1.txt")
                    .sha256("abc123")
                    .metadata(java.util.Map.of("resultArtifactId", "result-1"))
                    .content("run audit")
                    .build();
        }

        private SessionSnapshot snapshot(String sessionId) {
            return SessionSnapshot.builder()
                    .sessionId(sessionId)
                    .status("ready")
                    .currentTask("plan")
                    .currentConfigArtifactId("config-1")
                    .latestRunArtifactId("result-1")
                    .latestDiagnosisArtifactId("diag-1")
                    .latestAuditArtifactId("audit-1")
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
        }
    }

    private static final class CapturingConversationService extends StubConversationService {
        private final List<String> commandNames = new ArrayList<>();
        private final List<ConversationCommandRequest> commandRequests = new ArrayList<>();
        private final List<String> turnInputs = new ArrayList<>();
        private final List<java.util.Map<String, Object>> turnAttributes = new ArrayList<>();
        private final boolean withCurrentConfig;

        private CapturingConversationService() {
            this(true);
        }

        private CapturingConversationService(boolean withCurrentConfig) {
            this.withCurrentConfig = withCurrentConfig;
        }

        @Override
        public ConversationResponse executeCommand(ConversationCommandRequest request) {
            commandNames.add(request.getCommandName());
            commandRequests.add(request);
            return super.executeCommand(request);
        }

        @Override
        public ConversationResponse sendUserTurn(String sessionId, String text) {
            turnInputs.add(text);
            return super.sendUserTurn(sessionId, text);
        }

        @Override
        public ConversationResponse sendUserTurn(String sessionId, String text, java.util.Map<String, Object> attributes) {
            turnInputs.add(text);
            turnAttributes.add(attributes == null ? java.util.Map.of() : java.util.Map.copyOf(attributes));
            return super.sendUserTurn(sessionId, text);
        }

        @Override
        public SessionSnapshot getSessionSnapshot(String sessionId) {
            return withCurrentConfig ? super.getSessionSnapshot(sessionId) : SessionSnapshot.builder()
                    .sessionId(sessionId)
                    .status("ready")
                    .currentTask("plan")
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
        }
    }

    private static final class WelcomeSummaryConversationService extends StubConversationService {
        @Override
        public SessionSnapshot getSessionSnapshot(String sessionId) {
            return SessionSnapshot.builder()
                    .sessionId(sessionId)
                    .summary("Action execution failed: AI config generation failed: AI_CONFIG_DATASET_TYPE_MISSING@source.type: source dataset type is required; AI_CONFIG_JDBC_URL_MISSING@source.jdbcUrl: source JDBC URL is required; AI_CONFIG_RESOURCE_NAME_MISSING@source.resourceName: source table name is required; AI_CONFIG_DATASET_TYPE_MISSING@target.type: target dataset type is required")
                    .status("ready")
                    .currentTask("doctor")
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
        }
    }
}

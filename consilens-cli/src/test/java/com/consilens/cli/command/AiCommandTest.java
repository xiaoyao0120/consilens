package com.consilens.cli.command;

import com.consilens.ai.conversation.api.ConversationService;
import com.consilens.ai.conversation.api.model.ConversationCommandRequest;
import com.consilens.ai.conversation.api.model.ConversationResponse;
import com.consilens.ai.conversation.api.model.ArtifactContentResponse;
import com.consilens.ai.conversation.api.model.ArtifactEntryDto;
import com.consilens.ai.conversation.api.model.CurrentConfigResponse;
import com.consilens.ai.conversation.api.model.MemoryEntryDto;
import com.consilens.ai.conversation.api.model.SessionRecoveryResponse;
import com.consilens.ai.conversation.api.model.SaveConfigResponse;
import com.consilens.ai.conversation.api.model.SessionSnapshot;
import com.consilens.ai.conversation.api.model.SessionSummaryDto;
import com.consilens.cli.ai.runtime.AiRuntimeContextKeys;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiCommandTest {

    @Test
    void shouldHandleSingleShotNaturalLanguageRequest() {
        AtomicReference<String> sessionRef = new AtomicReference<>();
        AtomicReference<String> inputRef = new AtomicReference<>();

        ConversationService service = new ConversationService() {
            @Override
            public SessionSnapshot startSession(String preferredSessionId, boolean fresh) {
                return snapshot(preferredSessionId == null ? "stub" : preferredSessionId);
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
                sessionRef.set(sessionId);
                inputRef.set(text);
                return ConversationResponse.builder()
                        .type(ConversationResponse.Type.MESSAGE)
                        .message("planned")
                        .session(snapshot(sessionId))
                        .build();
            }

            @Override
            public ConversationResponse executeCommand(ConversationCommandRequest request) {
                throw new UnsupportedOperationException();
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
                return SessionRecoveryResponse.builder().session(snapshot(sessionId)).summary("recovered").build();
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
            public List<com.consilens.ai.conversation.api.model.MemoryEntryDto> listMemory(String sessionId, int limit) {
                return List.of();
            }

            @Override
            public List<ArtifactEntryDto> listArtifacts(String sessionId, int limit) {
                return List.of();
            }

            @Override
            public ArtifactContentResponse getArtifact(String artifactId) {
                return ArtifactContentResponse.builder().found(false).artifactId(artifactId).build();
            }

            private SessionSnapshot snapshot(String sessionId) {
                return SessionSnapshot.builder()
                        .sessionId(sessionId)
                        .status("ready")
                        .currentTask("doctor")
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
            }
        };

        int exitCode = new CommandLine(new AiCommand(() -> service))
                .execute("--session", "repl-1", "--no-llm", "compare", "orders");

        assertEquals(0, exitCode);
        assertEquals("repl-1", sessionRef.get());
        assertEquals("compare orders", inputRef.get());
    }

    @Test
    void shouldLoadStartupConfigAndBackendHints() throws Exception {
        List<ConversationCommandRequest> commands = new ArrayList<>();
        AtomicReference<String> userTurn = new AtomicReference<>();
        Path config = Files.createTempFile("ai-startup", ".yaml");
        Files.writeString(config, "source: {}\n");

        ConversationService service = new ConversationService() {
            @Override
            public SessionSnapshot startSession(String preferredSessionId, boolean fresh) {
                return snapshot(preferredSessionId == null ? "stub" : preferredSessionId);
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
                userTurn.set(text);
                return ConversationResponse.builder().type(ConversationResponse.Type.MESSAGE).message("turn").session(snapshot(sessionId)).build();
            }

            @Override
            public ConversationResponse executeCommand(ConversationCommandRequest request) {
                commands.add(request);
                return ConversationResponse.builder()
                        .type(ConversationResponse.Type.MESSAGE)
                        .message("ok")
                        .session(snapshot(request.getSessionId()))
                        .build();
            }

            @Override
            public ConversationResponse approve(String sessionId) { throw new UnsupportedOperationException(); }
            @Override
            public ConversationResponse deny(String sessionId) { throw new UnsupportedOperationException(); }
            @Override
            public SessionSnapshot getSessionSnapshot(String sessionId) { return snapshot(sessionId); }
            @Override
            public SessionRecoveryResponse recoverSession(String sessionId) { return SessionRecoveryResponse.builder().session(snapshot(sessionId)).build(); }
            @Override
            public CurrentConfigResponse getCurrentConfig(String sessionId) { throw new UnsupportedOperationException(); }
            @Override
            public SaveConfigResponse saveCurrentConfig(String sessionId, String path) { throw new UnsupportedOperationException(); }
            @Override
            public List<MemoryEntryDto> listMemory(String sessionId, int limit) { return List.of(); }
            @Override
            public List<ArtifactEntryDto> listArtifacts(String sessionId, int limit) { return List.of(); }
            @Override
            public ArtifactContentResponse getArtifact(String artifactId) { return ArtifactContentResponse.builder().found(false).artifactId(artifactId).build(); }

            private SessionSnapshot snapshot(String sessionId) {
                return SessionSnapshot.builder()
                        .sessionId(sessionId)
                        .status("ready")
                        .currentTask("doctor")
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
            }
        };

        int exitCode = new CommandLine(new AiCommand(() -> service))
                .execute("--session", "repl-2",
                        "--config", config.toString(),
                        "--backend", "openai",
                        "--base-url", "https://example.invalid/v1",
                        "--api-key", "test-key",
                        "compare", "orders");

        assertEquals(0, exitCode);
        assertEquals("use-config", commands.get(0).getCommandName());
        assertEquals(config.toString(), commands.get(0).getAttributes().get(AiRuntimeContextKeys.CONFIG_PATH));
        assertEquals(1, commands.size());
        assertEquals("compare orders", userTurn.get());
    }

    @Test
    void shouldRejectInteractiveStartupWhenBackendConfigMissing() {
        int exitCode = new CommandLine(new AiCommand(() -> {
            throw new AssertionError("Conversation service should not be created when backend validation fails.");
        })).execute("--backend", "openai", "--base-url", "https://example.invalid/v1");

        assertEquals(1, exitCode);
    }
}

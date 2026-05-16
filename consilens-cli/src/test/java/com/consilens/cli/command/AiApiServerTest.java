package com.consilens.cli.command;

import com.consilens.ai.conversation.api.ConversationService;
import com.consilens.ai.conversation.api.model.ConversationCommandRequest;
import com.consilens.ai.conversation.api.model.ConversationEventDto;
import com.consilens.ai.conversation.api.model.ConversationResponse;
import com.consilens.ai.conversation.api.model.ArtifactContentResponse;
import com.consilens.ai.conversation.api.model.ArtifactEntryDto;
import com.consilens.ai.conversation.api.model.CurrentConfigResponse;
import com.consilens.ai.conversation.api.model.MemoryEntryDto;
import com.consilens.ai.conversation.api.model.SessionRecoveryResponse;
import com.consilens.ai.conversation.api.model.SaveConfigResponse;
import com.consilens.ai.conversation.api.model.SessionSnapshot;
import com.consilens.ai.conversation.api.model.SessionSummaryDto;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.cli.ai.runtime.AiRuntimeContextKeys;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiApiServerTest {

    @Test
    void shouldServeConversationEndpoints() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        StubConversationService service = new StubConversationService();
        AiApiServer.RunningServer server = new AiApiServer(service, mapper, "token-1").start("127.0.0.1", 0);
        HttpClient client = HttpClient.newHttpClient();

        try {
            int port = server.port();
            Map<String, Object> health = requestJson(client, mapper,
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build());
            assertTrue((Boolean) health.get("success"));

            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversation/sessions")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

            Map<String, Object> create = requestJson(client, mapper,
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversation/sessions"))
                            .header("Authorization", "Bearer token-1")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"sessionId\":\"s-http\",\"fresh\":true}"))
                            .build());
            assertTrue((Boolean) create.get("success"));

            Map<String, Object> turn = requestJson(client, mapper,
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversation/sessions/s-http/turn"))
                            .header("Authorization", "Bearer token-1")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"compare orders\"}"))
                            .build());
            assertTrue((Boolean) turn.get("success"));

            Map<String, Object> loadConfig = requestJson(client, mapper,
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversation/sessions/s-http/config"))
                            .header("Authorization", "Bearer token-1")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"/tmp/http-use-config.yaml\"}"))
                            .build());
            assertTrue((Boolean) loadConfig.get("success"));
            assertEquals("use-config", service.lastCommandName);
            assertEquals("/tmp/http-use-config.yaml", service.lastConfigPath);

            Map<String, Object> plan = requestJson(client, mapper,
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversation/sessions/s-http/command"))
                            .header("Authorization", "Bearer token-1")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"commandName\":\"plan\",\"argument\":\"compare orders\",\"attributes\":{\"configRequest\":{\"sessionId\":\"s-http\",\"goal\":\"compare orders\",\"hints\":[\"backend=openai\",\"apiKey=top-secret\"]}}}"))
                            .build());
            assertTrue((Boolean) plan.get("success"));
            assertEquals("plan", service.lastCommandName);
            assertEquals("compare orders", service.lastConfigRequest.getGoal());
            assertTrue(service.lastConfigRequest.getHints().contains("backend=openai"));

            Map<String, Object> save = requestJson(client, mapper,
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversation/sessions/s-http/config/save"))
                            .header("Authorization", "Bearer token-1")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"/tmp/s-http.yaml\"}"))
                            .build());
            assertTrue((Boolean) save.get("success"));
            assertEquals("/tmp/s-http.yaml", service.lastSavedPath);

            Map<String, Object> remember = requestJson(client, mapper,
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversation/sessions/s-http/memory"))
                            .header("Authorization", "Bearer token-1")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"project\",\"content\":\"repo uses diff-record json\"}"))
                            .build());
            assertTrue((Boolean) remember.get("success"));

            Map<String, Object> memoryList = requestJson(client, mapper,
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversation/sessions/s-http/memory"))
                            .header("Authorization", "Bearer token-1")
                            .GET().build());
            assertTrue(((List<?>) memoryList.get("data")).size() == 1);

            Map<String, Object> recovery = requestJson(client, mapper,
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/conversation/sessions/s-http/recovery"))
                            .header("Authorization", "Bearer token-1")
                            .GET().build());
            assertTrue(((Map<?, ?>) recovery.get("data")).containsKey("recommendedAction"));

            Map<String, Object> artifacts = requestJson(client, mapper,
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversation/sessions/s-http/artifacts?type=RUN_AUDIT"))
                            .header("Authorization", "Bearer token-1")
                            .GET().build());
            assertTrue(((List<?>) artifacts.get("data")).size() == 1);

            Map<String, Object> artifact = requestJson(client, mapper,
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversation/artifacts/audit-1"))
                            .header("Authorization", "Bearer token-1")
                            .GET().build());
            assertTrue((Boolean) ((Map<?, ?>) artifact.get("data")).get("found"));
            assertTrue(((Map<?, ?>) artifact.get("data")).containsKey("metadata"));

            HttpResponse<String> rawArtifact = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversation/artifacts/audit-1/raw"))
                            .header("Authorization", "Bearer token-1")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, rawArtifact.statusCode());
            assertEquals("run audit", rawArtifact.body());

            HttpResponse<String> stream = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversation/sessions/s-http/command/stream"))
                            .header("Authorization", "Bearer token-1")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"commandName\":\"run\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, stream.statusCode());
            assertTrue(stream.body().contains("event: stage"));
            assertTrue(stream.body().contains("\"stage\":\"validate\""));
            assertTrue(stream.body().contains("\"status\":\"completed\""));
            assertTrue(stream.body().contains("event: result"));
            assertTrue(stream.body().contains("event: complete"));
            assertTrue(stream.body().contains("\"latestAuditArtifactId\":\"audit-1\""));

            Map<String, Object> forget = requestJson(client, mapper,
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversation/sessions/s-http/memory/memory-1"))
                            .header("Authorization", "Bearer token-1")
                            .DELETE()
                            .build());
            assertTrue((Boolean) forget.get("success"));
        } finally {
            server.stop();
        }
    }

    private Map<String, Object> requestJson(HttpClient client, ObjectMapper mapper, HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {
        });
    }

    private static class StubConversationService implements ConversationService {
        private final Map<String, SessionSnapshot> sessions = new HashMap<>();
        private final List<MemoryEntryDto> memories = new java.util.ArrayList<>();
        private final List<ArtifactEntryDto> artifacts = List.of(ArtifactEntryDto.builder()
                .artifactId("audit-1")
                .sessionId("s-http")
                .type("RUN_AUDIT")
                .path("/tmp/audit-1.txt")
                .sha256("abc123")
                .metadata(Map.of("resultArtifactId", "result-1"))
                .createdAt(Instant.now())
                .build());
        private String lastSavedPath;
        private String lastCommandName;
        private String lastConfigPath;
        private ConfigGenerationRequest lastConfigRequest;

        @Override
        public SessionSnapshot startSession(String preferredSessionId, boolean fresh) {
            SessionSnapshot snapshot = snapshot(preferredSessionId == null ? "s-default" : preferredSessionId);
            sessions.put(snapshot.getSessionId(), snapshot);
            return snapshot;
        }

        @Override
        public SessionSnapshot resumeSession(String sessionId) {
            return sessions.computeIfAbsent(sessionId, this::snapshot);
        }

        @Override
        public List<SessionSummaryDto> listSessions(int limit) {
            return sessions.values().stream()
                    .limit(limit)
                    .map(s -> SessionSummaryDto.builder()
                            .sessionId(s.getSessionId())
                            .status(s.getStatus())
                            .currentTask(s.getCurrentTask())
                            .lastActiveAt(s.getUpdatedAt())
                            .build())
                    .collect(java.util.stream.Collectors.toList());
        }

        @Override
        public ConversationResponse sendUserTurn(String sessionId, String text) {
            return ConversationResponse.builder()
                    .type(ConversationResponse.Type.MESSAGE)
                    .message("ok")
                    .session(resumeSession(sessionId))
                    .build();
        }

        @Override
        public ConversationResponse executeCommand(ConversationCommandRequest request) {
            lastCommandName = request.getCommandName();
            lastConfigPath = request.getAttributes() == null ? null : String.valueOf(request.getAttributes().get(AiRuntimeContextKeys.CONFIG_PATH));
            Object configRequest = request.getAttributes() == null ? null : request.getAttributes().get(AiRuntimeContextKeys.CONFIG_REQUEST);
            if (configRequest instanceof ConfigGenerationRequest) {
                lastConfigRequest = (ConfigGenerationRequest) configRequest;
            }
            if ("remember".equals(request.getCommandName())) {
                memories.add(MemoryEntryDto.builder()
                        .id("memory-" + (memories.size() + 1))
                        .type(String.valueOf(request.getAttributes().get("memoryType")))
                        .content(String.valueOf(request.getAttributes().get("memoryContent")))
                        .source("manual:" + request.getSessionId())
                        .createdAt(Instant.now())
                        .build());
            }
            if ("forget".equals(request.getCommandName()) && request.getArgument() != null) {
                memories.removeIf(memory -> request.getArgument().equals(memory.getId()));
            }
            return ConversationResponse.builder()
                    .type(ConversationResponse.Type.MESSAGE)
                    .message("command")
                    .events(List.of(
                            ConversationEventDto.builder()
                                    .stage("validate")
                                    .status("completed")
                                    .message("validation passed")
                                    .artifactId("validation-1")
                                    .artifactType("VALIDATION")
                                    .build(),
                            ConversationEventDto.builder()
                                    .stage("audit")
                                    .status("completed")
                                    .message("run audit persisted")
                                    .artifactId("audit-1")
                                    .artifactType("RUN_AUDIT")
                                    .build()))
                    .session(resumeSession(request.getSessionId()))
                    .build();
        }

        @Override
        public ConversationResponse approve(String sessionId) {
            return ConversationResponse.builder()
                    .type(ConversationResponse.Type.MESSAGE)
                    .message("approved")
                    .session(resumeSession(sessionId))
                    .build();
        }

        @Override
        public ConversationResponse deny(String sessionId) {
            return ConversationResponse.builder()
                    .type(ConversationResponse.Type.MESSAGE)
                    .message("denied")
                    .session(resumeSession(sessionId))
                    .build();
        }

        @Override
        public SessionSnapshot getSessionSnapshot(String sessionId) {
            return resumeSession(sessionId);
        }

        @Override
        public SessionRecoveryResponse recoverSession(String sessionId) {
            return SessionRecoveryResponse.builder()
                    .session(resumeSession(sessionId))
                    .latestAudit(getArtifact("audit-1"))
                    .latestDiagnosis(getArtifact("audit-1"))
                    .recommendedAction("repair")
                    .summary("Recovered session " + sessionId)
                    .build();
        }

        @Override
        public CurrentConfigResponse getCurrentConfig(String sessionId) {
            return CurrentConfigResponse.builder()
                    .found(true)
                    .artifactId("artifact-1")
                    .contentType("text/yaml")
                    .content("key: value")
                    .build();
        }

        @Override
        public SaveConfigResponse saveCurrentConfig(String sessionId, String path) {
            this.lastSavedPath = path;
            return SaveConfigResponse.builder()
                    .saved(true)
                    .artifactId("artifact-1")
                    .path(path)
                    .message("saved")
                    .build();
        }

        @Override
        public List<MemoryEntryDto> listMemory(String sessionId, int limit) {
            return memories;
        }

        @Override
        public List<ArtifactEntryDto> listArtifacts(String sessionId, int limit) {
            return artifacts;
        }

        @Override
        public ArtifactContentResponse getArtifact(String artifactId) {
            return ArtifactContentResponse.builder()
                    .found("audit-1".equals(artifactId))
                    .artifactId(artifactId)
                    .sessionId("s-http")
                    .type("RUN_AUDIT")
                    .path("/tmp/audit-1.txt")
                    .sha256("abc123")
                    .contentType("text/plain")
                    .content("run audit")
                    .metadata(Map.of("resultArtifactId", "result-1"))
                    .createdAt(Instant.now())
                    .build();
        }

        private SessionSnapshot snapshot(String sessionId) {
            return SessionSnapshot.builder()
                    .sessionId(sessionId)
                    .status("ready")
                    .currentTask("doctor")
                    .latestAuditArtifactId("audit-1")
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
        }
    }
}

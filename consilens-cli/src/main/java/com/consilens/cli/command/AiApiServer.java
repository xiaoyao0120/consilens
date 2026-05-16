package com.consilens.cli.command;

import com.consilens.ai.conversation.api.ConversationService;
import com.consilens.ai.conversation.api.model.ArtifactContentResponse;
import com.consilens.ai.conversation.api.model.ArtifactEntryDto;
import com.consilens.ai.conversation.api.model.ConversationEventDto;
import com.consilens.ai.conversation.api.model.ConversationCommandRequest;
import com.consilens.ai.conversation.api.model.ConversationResponse;
import com.consilens.ai.conversation.api.model.SessionRecoveryResponse;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.cli.ai.runtime.AiRuntimeContextKeys;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * HTTP adapter implementation for conversation service.
 */
final class AiApiServer {

    private final ConversationService service;
    private final ObjectMapper mapper;
    private final String authToken;

    AiApiServer(ConversationService service, ObjectMapper mapper) {
        this(service, mapper, null);
    }

    AiApiServer(ConversationService service, ObjectMapper mapper, String authToken) {
        this.service = service;
        this.mapper = mapper;
        this.authToken = authToken == null || authToken.isBlank() ? null : authToken.trim();
    }

    RunningServer start(String host, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return new RunningServer(server);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            URI uri = exchange.getRequestURI();
            List<String> segments = pathSegments(uri.getPath());
            if ("GET".equalsIgnoreCase(method) && segments.size() == 1 && "health".equals(segments.get(0))) {
                writeSuccess(exchange, 200, Map.of("status", "ok"));
                return;
            }
            if (!isAuthorized(exchange)) {
                writeError(exchange, 401, "Unauthorized");
                return;
            }
            segments = normalizeApiSegments(segments);
            if (segments.size() < 3
                    || !"api".equals(segments.get(0))
                    || !"conversation".equals(segments.get(1))) {
                writeError(exchange, 404, "Not found");
                return;
            }

            if ("artifacts".equals(segments.get(2))) {
                if (segments.size() == 4 && "GET".equalsIgnoreCase(method)) {
                    writeSuccess(exchange, 200, service.getArtifact(decode(segments.get(3))));
                    return;
                }
                if (segments.size() == 5
                        && "raw".equals(segments.get(4))
                        && "GET".equalsIgnoreCase(method)) {
                    writeArtifactRaw(exchange, service.getArtifact(decode(segments.get(3))));
                    return;
                }
                writeError(exchange, 404, "Not found");
                return;
            }

            if (!"sessions".equals(segments.get(2))) {
                writeError(exchange, 404, "Not found");
                return;
            }

            if (segments.size() == 3) {
                if ("POST".equalsIgnoreCase(method)) {
                    Map<String, Object> body = readJsonMap(exchange);
                    String preferredSessionId = asString(body.get("sessionId"));
                    boolean fresh = asBoolean(body.get("fresh"));
                    writeSuccess(exchange, 200, service.startSession(preferredSessionId, fresh));
                    return;
                }
                if ("GET".equalsIgnoreCase(method)) {
                    int limit = asInt(queryMap(uri).get("limit"), 20);
                    writeSuccess(exchange, 200, service.listSessions(limit));
                    return;
                }
            }

            if (segments.size() >= 4) {
                String sessionId = decode(segments.get(3));
                if (segments.size() == 4 && "GET".equalsIgnoreCase(method)) {
                    writeSuccess(exchange, 200, service.getSessionSnapshot(sessionId));
                    return;
                }
                if (segments.size() == 5) {
                    String action = segments.get(4);
                    if ("turn".equals(action) && "POST".equalsIgnoreCase(method)) {
                        Map<String, Object> body = readJsonMap(exchange);
                        writeSuccess(exchange, 200, service.sendUserTurn(sessionId, asString(body.get("text"))));
                        return;
                    }
                    if ("clarification".equals(action) && "POST".equalsIgnoreCase(method)) {
                        Map<String, Object> body = readJsonMap(exchange);
                        writeSuccess(exchange, 200, service.sendUserTurn(sessionId, asString(body.get("answer"))));
                        return;
                    }
                    if ("command".equals(action) && "POST".equalsIgnoreCase(method)) {
                        Map<String, Object> body = readJsonMap(exchange);
                        writeSuccess(exchange, 200, service.executeCommand(commandRequest(sessionId, body)));
                        return;
                    }
                    if ("approve".equals(action) && "POST".equalsIgnoreCase(method)) {
                        writeSuccess(exchange, 200, service.approve(sessionId));
                        return;
                    }
                    if ("deny".equals(action) && "POST".equalsIgnoreCase(method)) {
                        writeSuccess(exchange, 200, service.deny(sessionId));
                        return;
                    }
                    if ("config".equals(action) && "GET".equalsIgnoreCase(method)) {
                        writeSuccess(exchange, 200, service.getCurrentConfig(sessionId));
                        return;
                    }
                    if ("config".equals(action) && "POST".equalsIgnoreCase(method)) {
                        Map<String, Object> body = readJsonMap(exchange);
                        writeSuccess(exchange, 200, service.executeCommand(useConfigRequest(sessionId, asString(body.get("path")))));
                        return;
                    }
                    if ("recovery".equals(action) && "GET".equalsIgnoreCase(method)) {
                        writeSuccess(exchange, 200, service.recoverSession(sessionId));
                        return;
                    }
                    if ("memory".equals(action) && "GET".equalsIgnoreCase(method)) {
                        int limit = asInt(queryMap(uri).get("limit"), 20);
                        writeSuccess(exchange, 200, service.listMemory(sessionId, limit));
                        return;
                    }
                    if ("artifacts".equals(action) && "GET".equalsIgnoreCase(method)) {
                        int limit = asInt(queryMap(uri).get("limit"), 50);
                        String typeFilter = asString(queryMap(uri).get("type"));
                        writeSuccess(exchange, 200, filterArtifacts(service.listArtifacts(sessionId, limit), typeFilter));
                        return;
                    }
                    if ("memory".equals(action) && "POST".equalsIgnoreCase(method)) {
                        Map<String, Object> body = readJsonMap(exchange);
                        String type = asString(body.get("type"));
                        String content = asString(body.get("content"));
                        ConversationCommandRequest.ConversationCommandRequestBuilder request = ConversationCommandRequest.builder()
                                .sessionId(sessionId)
                                .commandName("remember")
                                .argument((type == null ? "" : type) + " " + (content == null ? "" : content));
                        if (type != null) {
                            request.attribute(AiRuntimeContextKeys.MEMORY_TYPE, type);
                        }
                        if (content != null) {
                            request.attribute(AiRuntimeContextKeys.MEMORY_CONTENT, content);
                        }
                        writeSuccess(exchange, 200, service.executeCommand(request.build()));
                        return;
                    }
                }
                if (segments.size() == 6
                        && "config".equals(segments.get(4))
                        && "save".equals(segments.get(5))
                        && "POST".equalsIgnoreCase(method)) {
                    Map<String, Object> body = readJsonMap(exchange);
                    writeSuccess(exchange, 200, service.saveCurrentConfig(sessionId, asString(body.get("path"))));
                    return;
                }
                if (segments.size() == 6
                        && "command".equals(segments.get(4))
                        && "stream".equals(segments.get(5))
                        && "POST".equalsIgnoreCase(method)) {
                    Map<String, Object> body = readJsonMap(exchange);
                    writeEventStream(exchange, commandRequest(sessionId, body));
                    return;
                }
                if (segments.size() == 6
                        && "memory".equals(segments.get(4))
                        && "DELETE".equalsIgnoreCase(method)) {
                    ConversationCommandRequest request = ConversationCommandRequest.builder()
                            .sessionId(sessionId)
                            .commandName("forget")
                            .argument(decode(segments.get(5)))
                            .attribute(AiRuntimeContextKeys.MEMORY_ID, decode(segments.get(5)))
                            .build();
                    writeSuccess(exchange, 200, service.executeCommand(request));
                    return;
                }
            }
            writeError(exchange, 404, "Not found");
        } catch (Exception e) {
            writeError(exchange, 500, "Internal error: " + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private Map<String, Object> readJsonMap(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return Map.of();
        }
        return mapper.readValue(body, new TypeReference<Map<String, Object>>() {
        });
    }

    private ConversationCommandRequest commandRequest(String sessionId, Map<String, Object> body) {
        ConversationCommandRequest.ConversationCommandRequestBuilder request = ConversationCommandRequest.builder()
                .sessionId(sessionId)
                .commandName(asString(body.get("commandName")))
                .argument(asString(body.get("argument")));
        Object attrs = body.get("attributes");
        if (attrs instanceof Map) {
            ((Map<?, ?>) attrs).forEach((key, value) -> request.attribute(String.valueOf(key), normalizeAttribute(String.valueOf(key), value)));
        }
        return request.build();
    }

    private ConversationCommandRequest useConfigRequest(String sessionId, String path) {
        return ConversationCommandRequest.builder()
                .sessionId(sessionId)
                .commandName("use-config")
                .argument(path)
                .attribute(AiRuntimeContextKeys.CONFIG_PATH, path)
                .build();
    }

    private Object normalizeAttribute(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (AiRuntimeContextKeys.CONFIG_REQUEST.equals(key) && value instanceof Map) {
            Map<?, ?> raw = (Map<?, ?>) value;
            ConfigGenerationRequest.ConfigGenerationRequestBuilder builder = ConfigGenerationRequest.builder()
                    .sessionId(asString(raw.get("sessionId")))
                    .goal(asString(raw.get("goal")));
            Object hints = raw.get("hints");
            if (hints instanceof Iterable<?>) {
                for (Object hint : (Iterable<?>) hints) {
                    builder.hint(asString(hint));
                }
            }
            return builder.build();
        }
        return value;
    }

    private Map<String, String> queryMap(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(query.split("&"))
                .map(item -> item.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> decode(pair[0]),
                        pair -> pair.length > 1 ? decode(pair[1]) : ""));
    }

    private List<String> pathSegments(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        return Arrays.stream(path.split("/"))
                .filter(segment -> !segment.isBlank())
                .collect(Collectors.toList());
    }

    private List<String> normalizeApiSegments(List<String> segments) {
        if (segments != null
                && segments.size() >= 3
                && "api".equals(segments.get(0))
                && "v1".equals(segments.get(1))
                && "conversation".equals(segments.get(2))) {
            java.util.ArrayList<String> normalized = new java.util.ArrayList<>();
            normalized.add("api");
            normalized.add("conversation");
            normalized.addAll(segments.subList(3, segments.size()));
            return normalized;
        }
        return segments;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return false;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private int asInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void writeSuccess(HttpExchange exchange, int status, Object data) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        writeJson(exchange, status, payload);
    }

    private void writeError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("error", message);
        writeJson(exchange, status, payload);
    }

    private void writeJson(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void writeArtifactRaw(HttpExchange exchange, ArtifactContentResponse artifact) throws IOException {
        if (artifact == null || !artifact.isFound() || artifact.getContent() == null) {
            writeError(exchange, 404, "Artifact not found");
            return;
        }
        byte[] bytes = artifact.getContent().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", artifact.getContentType() == null
                ? "text/plain; charset=utf-8"
                : artifact.getContentType() + "; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + artifact.getArtifactId() + "\"");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void writeEventStream(HttpExchange exchange, ConversationCommandRequest request) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream output = exchange.getResponseBody();
        ConversationResponse response = service.executeCommand(request);
        List<ConversationEventDto> events = response == null || response.getEvents() == null
                ? List.of()
                : response.getEvents();
        if (events.isEmpty()) {
            for (String stage : stagesFor(request == null ? null : request.getCommandName())) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("commandName", request == null ? null : request.getCommandName());
                payload.put("stage", stage);
                writeSse(output, "stage", payload);
            }
        } else {
            for (ConversationEventDto event : events) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("commandName", request == null ? null : request.getCommandName());
                payload.put("stage", event.getStage());
                payload.put("status", event.getStatus());
                payload.put("message", event.getMessage());
                payload.put("artifactId", event.getArtifactId());
                payload.put("artifactType", event.getArtifactType());
                payload.put("metadata", event.getMetadata());
                writeSse(output, "stage", payload);
            }
        }
        writeSse(output, "result", response);
        writeSse(output, "complete", response == null ? null : response.getSession());
        output.flush();
    }

    private List<ArtifactEntryDto> filterArtifacts(List<ArtifactEntryDto> artifacts, String typeFilter) {
        if (artifacts == null || artifacts.isEmpty() || typeFilter == null || typeFilter.isBlank()) {
            return artifacts == null ? List.of() : artifacts;
        }
        String normalized = typeFilter.trim().toLowerCase();
        return artifacts.stream()
                .filter(artifact -> artifact.getType() != null
                        && artifact.getType().toLowerCase().contains(normalized))
                .collect(Collectors.toList());
    }

    private boolean isAuthorized(HttpExchange exchange) {
        if (authToken == null) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.equals("Bearer " + authToken);
    }

    private void writeSse(OutputStream output, String event, Object payload) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("event: ").append(event).append("\n");
        String data = mapper.writeValueAsString(payload);
        builder.append("data: ").append(data).append("\n\n");
        output.write(builder.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private List<String> stagesFor(String commandName) {
        if (commandName == null) {
            return List.of("start");
        }
        switch (commandName) {
            case "run":
                return List.of("validate", "dry-run", "approval", "diff", "analyze");
            case "diff":
                return List.of("validate", "dry-run", "approval", "diff");
            case "repair":
                return List.of("load-failure-context", "generate-patch", "ready-for-retry");
            case "plan":
                return List.of("clarify", "generate-config", "validate");
            default:
                return List.of("execute");
        }
    }

    static final class RunningServer {
        private final HttpServer server;

        private RunningServer(HttpServer server) {
            this.server = server;
        }

        int port() {
            return server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }
}

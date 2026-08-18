package com.consilens.agent.model.openai;

import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.model.AgentMessageRole;
import com.consilens.agent.api.model.AgentModelEventListener;
import com.consilens.agent.api.model.AgentModelFinishReason;
import com.consilens.agent.api.model.AgentModelMessage;
import com.consilens.agent.api.model.AgentModelRequest;
import com.consilens.agent.api.model.AgentModelResponse;
import com.consilens.agent.api.model.AgentModelToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleModelClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private OpenAiCompatibleModelClient client(MockWebServer server, boolean stream) {
        return new OpenAiCompatibleModelClient(OpenAiCompatibleClientConfig.builder()
                .baseUrl(server.url("/").toString())
                .apiKey("test-key")
                .model("deepseek-chat")
                .stream(stream)
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .maxRetries(2)
                .build());
    }

    private AgentModelRequest request() {
        return AgentModelRequest.builder()
                .messages(List.of(AgentModelMessage.builder()
                        .role(AgentMessageRole.USER)
                        .content("hello")
                        .build()))
                .build();
    }

    @Test
    void parsesPlainTextResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(
                    "{\"choices\":[{\"message\":{\"content\":\"hi there\"},\"finish_reason\":\"stop\"}],"
                            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}"));
            OpenAiCompatibleModelClient client = client(server, false);

            AgentModelResponse response = client.complete(request(), new NoopListener(),
                    AgentCancellationToken.NEVER_CANCELLED);

            assertFalse(response.isFailed());
            assertEquals("hi there", response.getText());
            assertEquals(AgentModelFinishReason.STOP, response.getFinishReason());
            assertEquals(10, response.getUsage().getPromptTokens());
            assertEquals(5, response.getUsage().getCompletionTokens());
            assertTrue(response.getToolCalls().isEmpty());
        }
    }

    @Test
    void parsesMultipleToolCallsInSourceOrder() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(
                    "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":["
                            + "{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"find_datasource\","
                            + "\"arguments\":\"{\\\"name\\\":\\\"prod\\\"}\"}},"
                            + "{\"id\":\"c2\",\"type\":\"function\",\"function\":{\"name\":\"list_types\","
                            + "\"arguments\":\"{}\"}}"
                            + "]},\"finish_reason\":\"tool_calls\"}],"
                            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));
            OpenAiCompatibleModelClient client = client(server, false);

            AgentModelResponse response = client.complete(request(), new NoopListener(),
                    AgentCancellationToken.NEVER_CANCELLED);

            assertEquals(AgentModelFinishReason.TOOL_CALLS, response.getFinishReason());
            assertEquals(2, response.getToolCalls().size());
            assertEquals("find_datasource", response.getToolCalls().get(0).getName());
            assertEquals("{\"name\":\"prod\"}", response.getToolCalls().get(0).getArguments());
            assertEquals("list_types", response.getToolCalls().get(1).getName());
        }
    }

    @Test
    void streamsDeltasAndRebuildsToolCalls() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"},\"finish_reason\":null}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":null}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                            + "\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"text\\\":\\\"\"}}]},"
                            + "\"finish_reason\":null}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                            + "\"function\":{\"arguments\":\"hi\\\"}\"}}]},\"finish_reason\":null}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                            + "data: [DONE]\n\n"));
            OpenAiCompatibleModelClient client = client(server, true);
            AtomicReference<String> deltaText = new AtomicReference<>("");
            AgentModelEventListener listener = new NoopListener() {
                @Override
                public void onDelta(String delta) {
                    deltaText.set(deltaText.get() + delta);
                }
            };

            AgentModelResponse response = client.complete(request(), listener,
                    AgentCancellationToken.NEVER_CANCELLED);

            assertEquals("Hello", deltaText.get());
            assertEquals("Hello", response.getText());
            assertEquals(AgentModelFinishReason.TOOL_CALLS, response.getFinishReason());
            assertEquals(1, response.getToolCalls().size());
            AgentModelToolCall call = response.getToolCalls().get(0);
            assertEquals("echo", call.getName());
            assertEquals("{\"text\":\"hi\"}", call.getArguments());
            assertTrue(response.isToolArgumentsTrusted());
        }
    }

    @Test
    void lengthFinishReasonWithToolCallsMarksArgumentsUntrusted() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(
                    "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":["
                            + "{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"create_datasource\","
                            + "\"arguments\":\"{\\\"host\\\":\\\"prod-db\\\"}\"}"
                            + "}]},\"finish_reason\":\"length\"}]}"));
            OpenAiCompatibleModelClient client = client(server, false);

            AgentModelResponse response = client.complete(request(), new NoopListener(),
                    AgentCancellationToken.NEVER_CANCELLED);

            assertEquals(AgentModelFinishReason.LENGTH, response.getFinishReason());
            assertFalse(response.isToolArgumentsTrusted());
            assertFalse(response.getToolCalls().isEmpty());
        }
    }

    @Test
    void rateLimitIsRetriedAndSucceeds() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(429));
            server.enqueue(new MockResponse().setBody(
                    "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));
            OpenAiCompatibleModelClient client = client(server, false);

            AgentModelResponse response = client.complete(request(), new NoopListener(),
                    AgentCancellationToken.NEVER_CANCELLED);

            assertFalse(response.isFailed());
            assertEquals("ok", response.getText());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    void fiveHundredMapsToRetryableFailureAfterRetries() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(500));
            OpenAiCompatibleModelClient client = client(server, false);

            AgentModelResponse response = client.complete(request(), new NoopListener(),
                    AgentCancellationToken.NEVER_CANCELLED);

            assertTrue(response.isFailed());
            assertEquals("MODEL_TRANSIENT_ERROR", response.getErrorCode());
            assertTrue(response.isRetryable());
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test
    void badRequestIsNotRetried() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(400));
            OpenAiCompatibleModelClient client = client(server, false);

            AgentModelResponse response = client.complete(request(), new NoopListener(),
                    AgentCancellationToken.NEVER_CANCELLED);

            assertTrue(response.isFailed());
            assertEquals("MODEL_CONFIG_INVALID", response.getErrorCode());
            assertFalse(response.isRetryable());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    void cancelledTokenNeverCallsTheProvider() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            OpenAiCompatibleModelClient client = client(server, false);

            AgentModelResponse response = client.complete(request(), new NoopListener(), () -> true);

            assertTrue(response.isFailed());
            assertEquals("MODEL_CANCELLED", response.getErrorCode());
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test
    void requestBodyCarriesToolsAndRoleMapping() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(
                    "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));
            OpenAiCompatibleModelClient client = client(server, false);
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            AgentModelRequest request = AgentModelRequest.builder()
                    .messages(List.of(AgentModelMessage.builder()
                            .role(AgentMessageRole.SYSTEM)
                            .content("be strict")
                            .build()))
                    .toolDefinitions(List.of(com.consilens.agent.api.model.AgentModelToolDefinition.builder()
                            .name("echo")
                            .description("echo text")
                            .inputSchema(schema)
                            .build()))
                    .build();

            client.complete(request, new NoopListener(), AgentCancellationToken.NEVER_CANCELLED);

            RecordedRequest recorded = server.takeRequest();
            assertEquals("Bearer test-key", recorded.getHeader("Authorization"));
            String body = recorded.getBody().readUtf8();
            assertTrue(body.contains("\"role\":\"system\""));
            assertTrue(body.contains("\"tools\""));
            assertTrue(body.contains("\"name\":\"echo\""));
            assertTrue(body.contains("\"stream\":false"));
        }
    }

    @Test
    void timeoutMapsToModelTimeout() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeadersDelay(5, TimeUnit.SECONDS)
                    .setBody("{}"));
            OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(
                    OpenAiCompatibleClientConfig.builder()
                            .baseUrl(server.url("/").toString())
                            .apiKey("k")
                            .model("m")
                            .stream(false)
                            .connectTimeout(Duration.ofMillis(300))
                            .readTimeout(Duration.ofMillis(300))
                            .maxRetries(0)
                            .build());

            AgentModelResponse response = client.complete(request(), new NoopListener(),
                    AgentCancellationToken.NEVER_CANCELLED);

            assertTrue(response.isFailed());
            assertEquals("MODEL_TIMEOUT", response.getErrorCode());
            assertTrue(response.isRetryable());
        }
    }

    private static class NoopListener implements AgentModelEventListener {
        private final AtomicInteger completed = new AtomicInteger();

        @Override
        public void onCompleted(AgentModelResponse response) {
            completed.incrementAndGet();
        }
    }
}

package com.consilens.agent.model.openai;

import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.model.AgentMessageRole;
import com.consilens.agent.api.model.AgentModelClient;
import com.consilens.agent.api.model.AgentModelEventListener;
import com.consilens.agent.api.model.AgentModelFinishReason;
import com.consilens.agent.api.model.AgentModelMessage;
import com.consilens.agent.api.model.AgentModelRequest;
import com.consilens.agent.api.model.AgentModelResponse;
import com.consilens.agent.api.model.AgentModelToolCall;
import com.consilens.agent.api.model.AgentModelToolDefinition;
import com.consilens.agent.api.model.AgentModelUsage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Native OpenAI-compatible transport covering OpenAI, DeepSeek and compatible
 * Ollama profiles. Supports synchronous and SSE streaming responses, retries
 * retryable HTTP errors with backoff, and honors cancellation.
 */
public final class OpenAiCompatibleModelClient implements AgentModelClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleModelClient.class);

    private final OpenAiCompatibleClientConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    public OpenAiCompatibleModelClient(OpenAiCompatibleClientConfig config) {
        this(config, new ObjectMapper());
    }

    public OpenAiCompatibleModelClient(OpenAiCompatibleClientConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        Duration connect = config.getConnectTimeout() == null ? Duration.ofSeconds(10) : config.getConnectTimeout();
        Duration read = config.getReadTimeout() == null ? Duration.ofSeconds(120) : config.getReadTimeout();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(connect.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(read.toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public AgentModelResponse complete(AgentModelRequest request,
                                       AgentModelEventListener listener,
                                       AgentCancellationToken cancellationToken) {
        long started = System.currentTimeMillis();
        String baseUrl = firstNonBlank(request.getBaseUrl(), config.getBaseUrl());
        String model = firstNonBlank(request.getModel(), config.getModel());
        if (baseUrl == null || model == null) {
            return failure("MODEL_CONFIG_INVALID", false, "model base url and model name are required", started);
        }

        int maxRetries = config.getMaxRetries();
        int attempt = 0;
        while (true) {
            if (cancellationToken.isCancelled()) {
                return failure("MODEL_CANCELLED", false, "cancelled", started);
            }
            try {
                AgentModelResponse response = attempt(baseUrl, model, request, listener, cancellationToken, started);
                if (!response.isFailed() || !response.isRetryable() || attempt >= maxRetries) {
                    logModelOutcome(response, attempt);
                    return response;
                }
            } catch (RetryableTransportException e) {
                if (!e.retryable || attempt >= maxRetries) {
                    log.warn("model request failed after {} attempt(s): errorCode={} message={}",
                            attempt + 1, e.errorCode, e.safeMessage);
                    return failure(e.errorCode, e.retryable, e.safeMessage, started);
                }
            }
            attempt++;
            long backoff = 250L * (1L << Math.min(attempt, 5)) + (long) (Math.random() * 150);
            log.warn("model request transient failure, retrying in {}ms (attempt {}/{})",
                    backoff, attempt, maxRetries);
            sleepQuietly(backoff);
        }
    }

    private void logModelOutcome(AgentModelResponse response, int attempt) {
        if (response.isFailed()) {
            log.warn("model request failed: errorCode={} retryable={} message={} durationMs={} attempt={}",
                    response.getErrorCode(), response.isRetryable(), response.getSafeMessage(),
                    response.getDurationMillis(), attempt + 1);
            return;
        }
        AgentModelUsage usage = response.getUsage();
        log.info("model request ok: model={} finishReason={} textChars={} toolCalls={} "
                        + "promptTokens={} completionTokens={} durationMs={}",
                config.getModel(), response.getFinishReason(),
                response.getText() == null ? 0 : response.getText().length(),
                response.getToolCalls() == null ? 0 : response.getToolCalls().size(),
                usage == null ? 0 : usage.getPromptTokens(),
                usage == null ? 0 : usage.getCompletionTokens(),
                response.getDurationMillis());
    }

    private AgentModelResponse attempt(String baseUrl,
                                       String model,
                                       AgentModelRequest request,
                                       AgentModelEventListener listener,
                                       AgentCancellationToken cancellationToken,
                                       long started) throws RetryableTransportException {
        HttpUrl url = HttpUrl.get(baseUrl + "/chat/completions");
        RequestBody body = RequestBody.create(JSON, buildRequestBody(model, request).toString());
        Request httpRequest = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + resolveKey(request))
                .post(body)
                .build();
        Call call = httpClient.newCall(httpRequest);
        try {
            if (config.isStream()) {
                return streamCall(call, listener, cancellationToken, started);
            }
            return syncCall(call, listener, started);
        } catch (RetryableTransportException e) {
            // Preserve the original classification (e.g. non-retryable config errors).
            throw e;
        } catch (IOException e) {
            if (cancellationToken.isCancelled()) {
                return failure("MODEL_CANCELLED", false, "cancelled", started);
            }
            if (e instanceof SocketTimeoutException) {
                throw new RetryableTransportException("MODEL_TIMEOUT", true,
                        "model request timed out");
            }
            throw new RetryableTransportException("MODEL_TRANSIENT_ERROR", true,
                    "model request failed: " + safeCause(e));
        }
    }

    private AgentModelResponse syncCall(Call call, AgentModelEventListener listener, long started)
            throws IOException {
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                handleHttpError(response);
            }
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "{}" : responseBody.string();
            JsonNode root = mapper.readTree(text);
            AgentModelResponse parsed = parseChatResponse(root, started);
            listener.onCompleted(parsed);
            return parsed;
        }
    }

    private AgentModelResponse streamCall(Call call,
                                          AgentModelEventListener listener,
                                          AgentCancellationToken cancellationToken,
                                          long started) throws IOException {
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                handleHttpError(response);
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("empty stream body");
            }
            StringBuilder text = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            List<AccumulatingToolCall> toolCalls = new ArrayList<>();
            AtomicReference<String> finishReason = new AtomicReference<>();
            AtomicLong promptTokens = new AtomicLong();
            AtomicLong completionTokens = new AtomicLong();
            boolean done = false;

            BufferedSource source = responseBody.source();
            while (!done) {
                if (cancellationToken.isCancelled()) {
                    return failure("MODEL_CANCELLED", false, "cancelled", started);
                }
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) {
                    done = true;
                    continue;
                }
                JsonNode chunk;
                try {
                    chunk = mapper.readTree(data);
                } catch (JsonProcessingException e) {
                    continue;
                }
                parseStreamChunk(chunk, listener, text, reasoning, toolCalls, finishReason,
                        promptTokens, completionTokens);
            }

            if (cancellationToken.isCancelled()) {
                return failure("MODEL_CANCELLED", false, "cancelled", started);
            }
            AgentModelResponse parsed = buildResponse(text.toString(), reasoning.toString(), toolCalls,
                    finishReason.get(), promptTokens.get(), completionTokens.get(), started);
            listener.onCompleted(parsed);
            return parsed;
        }
    }

    private void parseStreamChunk(JsonNode chunk,
                                  AgentModelEventListener listener,
                                  StringBuilder text,
                                  StringBuilder reasoning,
                                  List<AccumulatingToolCall> toolCalls,
                                  AtomicReference<String> finishReason,
                                  AtomicLong promptTokens,
                                  AtomicLong completionTokens) {
        JsonNode choices = chunk.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.get("delta");
            if (delta != null) {
                JsonNode reasoningDelta = delta.get("reasoning_content");
                if (reasoningDelta != null && !reasoningDelta.isNull()) {
                    reasoning.append(reasoningDelta.asText());
                }
                JsonNode content = delta.get("content");
                if (content != null && !content.isNull()) {
                    String piece = content.asText();
                    text.append(piece);
                    listener.onDelta(piece);
                }
                JsonNode deltaToolCalls = delta.get("tool_calls");
                if (deltaToolCalls != null && deltaToolCalls.isArray()) {
                    for (JsonNode item : deltaToolCalls) {
                        int index = item.path("index").asInt(0);
                        while (toolCalls.size() <= index) {
                            toolCalls.add(new AccumulatingToolCall());
                        }
                        AccumulatingToolCall accumulator = toolCalls.get(index);
                        JsonNode id = item.get("id");
                        if (id != null && !id.isNull()) {
                            accumulator.id = id.asText();
                        }
                        JsonNode function = item.get("function");
                        if (function != null) {
                            JsonNode name = function.get("name");
                            if (name != null && !name.isNull()) {
                                accumulator.name.append(name.asText());
                            }
                            JsonNode arguments = function.get("arguments");
                            if (arguments != null && !arguments.isNull()) {
                                accumulator.arguments.append(arguments.asText());
                            }
                            listener.onToolCallDelta(index, accumulator.name.toString(),
                                    accumulator.arguments.toString());
                        }
                    }
                }
            }
            JsonNode finish = choice.get("finish_reason");
            if (finish != null && !finish.isNull()) {
                finishReason.set(finish.asText());
            }
        }
        JsonNode usage = chunk.get("usage");
        if (usage != null && usage.isObject()) {
            promptTokens.set(usage.path("prompt_tokens").asLong(0));
            completionTokens.set(usage.path("completion_tokens").asLong(0));
        }
    }

    private AgentModelResponse parseChatResponse(JsonNode root, long started) {
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.size() == 0) {
            return failure("MODEL_TRANSIENT_ERROR", true, "empty choices in model response", started);
        }
        JsonNode message = choices.get(0).get("message");
        String text = message == null ? null : message.path("content").asText(null);
        String reasoning = message == null ? null
                : message.path("reasoning_content").asText(null);
        List<AccumulatingToolCall> toolCalls = new ArrayList<>();
        if (message != null && message.has("tool_calls")) {
            for (JsonNode item : message.get("tool_calls")) {
                AccumulatingToolCall acc = new AccumulatingToolCall();
                acc.id = item.path("id").asText(null);
                acc.name.append(item.path("function").path("name").asText(""));
                acc.arguments.append(item.path("function").path("arguments").asText(""));
                toolCalls.add(acc);
            }
        }
        String finishReason = choices.get(0).path("finish_reason").asText(null);
        JsonNode usage = root.get("usage");
        long prompt = usage == null ? 0 : usage.path("prompt_tokens").asLong(0);
        long completion = usage == null ? 0 : usage.path("completion_tokens").asLong(0);
        return buildResponse(text, reasoning, toolCalls, finishReason, prompt, completion, started);
    }

    private AgentModelResponse buildResponse(String text,
                                             String reasoningContent,
                                             List<AccumulatingToolCall> toolCalls,
                                             String finishReason,
                                             long promptTokens,
                                             long completionTokens,
                                             long started) {
        boolean truncated = "length".equals(finishReason) && !toolCalls.isEmpty();
        List<AgentModelToolCall> calls = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            AccumulatingToolCall acc = toolCalls.get(i);
            calls.add(AgentModelToolCall.builder()
                    .id(acc.id == null ? "call_" + i : acc.id)
                    .name(acc.name.toString())
                    .arguments(acc.arguments.toString())
                    .index(i)
                    .build());
        }
        return AgentModelResponse.success(
                text,
                calls,
                mapFinishReason(finishReason),
                AgentModelUsage.builder()
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(promptTokens + completionTokens)
                        .build(),
                !truncated,
                System.currentTimeMillis() - started,
                reasoningContent);
    }

    private AgentModelFinishReason mapFinishReason(String finishReason) {
        if (finishReason == null) {
            return AgentModelFinishReason.STOP;
        }
        switch (finishReason) {
            case "tool_calls":
                return AgentModelFinishReason.TOOL_CALLS;
            case "length":
                return AgentModelFinishReason.LENGTH;
            case "content_filter":
                return AgentModelFinishReason.CONTENT_FILTER;
            default:
                return AgentModelFinishReason.STOP;
        }
    }

    /**
     * Throws {@link RetryableTransportException} for 429/5xx so the caller can
     * retry; returns a non-retryable failure response for other HTTP errors.
     */
    private void handleHttpError(Response response) throws RetryableTransportException {
        int code = response.code();
        String body = "";
        try (ResponseBody responseBody = response.body()) {
            if (responseBody != null) {
                body = responseBody.string();
            }
        } catch (IOException ignored) {
            // 响应体读取失败不掩盖原始状态码
        }
        String safeMessage = "model provider error " + code
                + (body.isBlank() ? "" : ": " + body);
        if (code == 429) {
            throw new RetryableTransportException("MODEL_RATE_LIMITED", true, safeMessage);
        }
        if (code >= 500) {
            throw new RetryableTransportException("MODEL_TRANSIENT_ERROR", true, safeMessage);
        }
        throw new RetryableTransportException("MODEL_CONFIG_INVALID", false, safeMessage);
    }

    private ObjectNode buildRequestBody(String model, AgentModelRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("stream", config.isStream());
        if (request.getTemperature() != null) {
            root.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            root.put("max_tokens", request.getMaxTokens());
        }
        ArrayNode messages = root.putArray("messages");
        for (AgentModelMessage message : request.getMessages() == null ? List.<AgentModelMessage>of()
                : request.getMessages()) {
            ObjectNode node = messages.addObject();
            node.put("role", mapRole(message.getRole()));
            if (message.getContent() != null) {
                node.put("content", message.getContent());
            }
            // DeepSeek thinking mode：assistant 历史必须回传 reasoning_content
            if (message.getRole() == AgentMessageRole.ASSISTANT
                    && message.getReasoningContent() != null
                    && !message.getReasoningContent().isBlank()) {
                node.put("reasoning_content", message.getReasoningContent());
            }
            if (message.getRole() == AgentMessageRole.TOOL && message.getToolCallId() != null) {
                node.put("tool_call_id", message.getToolCallId());
            }
            if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                ArrayNode toolCalls = node.putArray("tool_calls");
                for (AgentModelToolCall call : message.getToolCalls()) {
                    ObjectNode callNode = toolCalls.addObject();
                    callNode.put("id", call.getId());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.getName());
                    function.put("arguments", call.getArguments());
                }
            }
        }
        if (request.getToolDefinitions() != null && !request.getToolDefinitions().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (AgentModelToolDefinition definition : request.getToolDefinitions()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode function = tool.putObject("function");
                function.put("name", definition.getName());
                function.put("description", definition.getDescription());
                function.set("parameters", definition.getInputSchema() == null
                        ? mapper.createObjectNode()
                        : definition.getInputSchema());
            }
        }
        return root;
    }

    private String mapRole(AgentMessageRole role) {
        switch (role) {
            case SYSTEM:
                return "system";
            case ASSISTANT:
                return "assistant";
            case TOOL:
                return "tool";
            case USER:
            default:
                return "user";
        }
    }

    private String resolveKey(AgentModelRequest request) {
        return firstNonBlank(request.getApiKey(), config.getApiKey());
    }

    private static AgentModelResponse failure(String code, boolean retryable, String message, long started) {
        return AgentModelResponse.failure(code, retryable, message, System.currentTimeMillis() - started);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String safeCause(IOException e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class AccumulatingToolCall {
        String id;
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }

    private static final class RetryableTransportException extends IOException {
        final String errorCode;
        final String safeMessage;
        final boolean retryable;

        RetryableTransportException(String errorCode, boolean retryable, String safeMessage) {
            this.errorCode = errorCode;
            this.retryable = retryable;
            this.safeMessage = safeMessage;
        }
    }
}

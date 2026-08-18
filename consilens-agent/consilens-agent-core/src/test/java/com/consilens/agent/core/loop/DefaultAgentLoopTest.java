package com.consilens.agent.core.loop;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.event.AgentEventVisibility;
import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.model.AgentMessageRole;
import com.consilens.agent.api.model.AgentModelFinishReason;
import com.consilens.agent.api.model.AgentModelMessage;
import com.consilens.agent.api.model.AgentModelRequest;
import com.consilens.agent.api.model.AgentModelResponse;
import com.consilens.agent.api.model.AgentModelToolCall;
import com.consilens.agent.api.model.AgentModelUsage;
import com.consilens.agent.api.state.AgentRunStatus;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.core.TestAgentModelClient;
import com.consilens.agent.core.event.AgentEventFactory;
import com.consilens.agent.core.event.AgentEventSequenceValidator;
import com.consilens.agent.core.store.InMemoryAgentPersistence;
import com.consilens.agent.core.tool.AgentToolRegistry;
import com.consilens.agent.core.tool.EchoTool;
import com.consilens.agent.core.tool.FakeApprovalTool;
import com.consilens.agent.core.tool.QuestionTool;
import com.consilens.agent.core.tool.SecretRefillTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentLoopTest {

    private InMemoryAgentPersistence persistence;
    private AgentSessionRecord session;
    private AgentToolRegistry registry;
    private TestAgentModelClient model;
    private DefaultAgentLoop loop;
    private int callIdCounter;

    @BeforeEach
    void setUp() {
        persistence = new InMemoryAgentPersistence();
        session = persistence.createSession(AgentSessionRecord.builder()
                .id("s1")
                .actorId("actor")
                .requestId("create-1")
                .status(AgentSessionStatus.READY)
                .workflowStage(com.consilens.agent.api.state.AgentWorkflowStage.DISCOVERY)
                .nextSeq(0)
                .snapshotSeq(0)
                .version(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        registry = new AgentToolRegistry(List.of(new EchoTool(), new QuestionTool()));
        callIdCounter = 0;
    }

    private AgentModelResponse text(String text) {
        return AgentModelResponse.success(text, List.of(), AgentModelFinishReason.STOP,
                AgentModelUsage.builder().promptTokens(5).completionTokens(3).totalTokens(8).build(),
                true, 1);
    }

    private AgentModelResponse toolCall(String name, String args) {
        return AgentModelResponse.success(null,
                List.of(AgentModelToolCall.builder()
                        .id("call_" + (++callIdCounter))
                        .name(name)
                        .arguments(args)
                        .index(0)
                        .build()),
                AgentModelFinishReason.TOOL_CALLS,
                AgentModelUsage.builder().promptTokens(5).completionTokens(3).totalTokens(8).build(),
                true, 1);
    }

    private AgentLoopResult run(String requestId, String userText, AgentModelResponse... script) {
        model = new TestAgentModelClient(script);
        loop = new DefaultAgentLoop(persistence, model, registry,
                new AgentContextAssembler("test", 50), AgentRunConfig.defaults());
        appendUserMessage(requestId, userText);
        return loop.run(AgentLoopRequest.builder()
                .sessionId("s1")
                .runId("run-" + requestId)
                .requestId(requestId)
                .actorId("actor")
                .workerId("worker")
                .objectiveId("obj-1")
                .objective("test objective")
                .trigger(AgentRunTrigger.USER_MESSAGE)
                .build(), AgentCancellationToken.NEVER_CANCELLED);
    }

    private void appendUserMessage(String requestId, String text) {
        long seq = persistence.nextSeq("s1");
        persistence.appendEvents("s1", seq, List.of(
                AgentEventFactory.create(AgentEventType.USER_MESSAGE, AgentEventVisibility.USER_AND_MODEL,
                        "s1", null, null,
                        AgentEventFactory.payload().put("requestId", requestId).put("text", text))));
    }

    private List<AgentEvent> allEvents() {
        return persistence.listEventsAfter("s1", -1, 1000);
    }

    @Test
    void plainTextAnswerCompletesTheRun() {
        AgentLoopResult result = run("m1", "hi", text("hello from model"));

        assertEquals(AgentLoopOutcome.COMPLETED, result.getOutcome());
        assertEquals(AgentSessionStatus.READY, result.getSessionStatus());
        assertEquals(AgentRunStatus.COMPLETED, result.getRunStatus());
        assertTrue(AgentEventSequenceValidator.validate(allEvents()).isEmpty());
        assertTrue(allEvents().stream().anyMatch(e -> e.getType() == AgentEventType.RUN_COMPLETED));
        assertEquals(AgentSessionStatus.READY, persistence.findSession("s1").orElseThrow().getStatus());
    }

    @Test
    void toolResultFeedsBackAndReducerAdvancesState() {
        AgentLoopResult result = run("m2", "echo hello",
                toolCall("echo", "{\"text\":\"hello\"}"),
                text("done"));

        assertEquals(AgentLoopOutcome.COMPLETED, result.getOutcome());
        assertEquals(2, model.playedCount());
        AgentModelRequest second = model.receivedRequests().get(1);
        AgentModelMessage toolMessage = second.getMessages().stream()
                .filter(m -> m.getRole() == AgentMessageRole.TOOL)
                .findFirst().orElseThrow();
        assertEquals("call_1", toolMessage.getToolCallId());
        assertTrue(toolMessage.getContent().contains("echoed: hello"));
        assertTrue(AgentEventSequenceValidator.validate(allEvents()).isEmpty());

        AgentWorkingState state = persistence.latestSnapshot("s1").orElseThrow().getWorkingState();
        assertTrue(state.getConfirmedAssumptions().contains("echo:hello"));
        assertEquals("ECHO", state.getLastCompletedStep());
    }

    @Test
    void unknownToolIsBlockedAndLoopContinues() {
        AgentLoopResult result = run("m3", "do something",
                toolCall("delete_everything", "{}"),
                text("recovered"));

        assertEquals(AgentLoopOutcome.COMPLETED, result.getOutcome());
        assertTrue(allEvents().stream().anyMatch(e ->
                e.getType() == AgentEventType.TOOL_BLOCKED
                        && "UNKNOWN_TOOL".equals(e.getPayload().path("errorCode").asText())));
        assertEquals(2, model.playedCount());
    }

    @Test
    void schemaViolationBlocksTheToolCall() {
        AgentLoopResult result = run("m4", "echo nothing",
                toolCall("echo", "{}"),
                text("fixed"));

        assertEquals(AgentLoopOutcome.COMPLETED, result.getOutcome());
        assertTrue(allEvents().stream().anyMatch(e ->
                e.getType() == AgentEventType.TOOL_BLOCKED
                        && "TOOL_ARGUMENT_INVALID".equals(e.getPayload().path("errorCode").asText())));
    }

    @Test
    void secretShapedArgumentsAreRejectedAndRedacted() {
        AgentLoopResult result = run("m5", "echo",
                toolCall("echo", "{\"text\":\"hi\",\"password\":\"hunter2\"}"),
                text("ok"));

        assertEquals(AgentLoopOutcome.COMPLETED, result.getOutcome());
        assertTrue(allEvents().stream().anyMatch(e ->
                e.getType() == AgentEventType.TOOL_BLOCKED
                        && ("SECRET_IN_MODEL_ARGUMENTS".equals(e.getPayload().path("errorCode").asText())
                        || "TOOL_ARGUMENT_INVALID".equals(e.getPayload().path("errorCode").asText()))));
        assertTrue(allEvents().stream().noneMatch(e -> e.getType() == AgentEventType.TOOL_STARTED));
        assertFalse(allEvents().toString().contains("hunter2"));
    }

    @Test
    void turnBudgetExhaustionFailsTheRunRetryable() {
        AgentModelResponse[] script = new AgentModelResponse[8];
        for (int i = 0; i < 8; i++) {
            script[i] = toolCall("echo", "{\"text\":\"x\"}");
        }

        AgentLoopResult result = run("m6", "keep going", script);

        assertEquals(AgentLoopOutcome.BUDGET_EXHAUSTED, result.getOutcome());
        assertTrue(result.isRetryable());
        assertEquals(AgentSessionStatus.FAILED, result.getSessionStatus());
        assertTrue(allEvents().stream().anyMatch(e ->
                e.getType() == AgentEventType.RUN_FAILED
                        && "AGENT_BUDGET_EXHAUSTED".equals(e.getPayload().path("errorCode").asText())));
    }

    @Test
    void cancellationStopsTheRunWithoutModelCalls() {
        TestAgentModelClient unused = new TestAgentModelClient(text("unused"));
        loop = new DefaultAgentLoop(persistence, unused, registry,
                new AgentContextAssembler("test", 50), AgentRunConfig.defaults());
        AtomicBoolean cancelled = new AtomicBoolean(true);
        appendUserMessage("m7", "please stop");

        AgentLoopResult result = loop.run(AgentLoopRequest.builder()
                .sessionId("s1")
                .runId("run-m7")
                .requestId("m7")
                .actorId("actor")
                .workerId("worker")
                .objectiveId("obj-1")
                .build(), cancelled::get);

        assertEquals(AgentLoopOutcome.CANCELLED, result.getOutcome());
        assertEquals(AgentSessionStatus.CANCELLED, result.getSessionStatus());
        assertTrue(allEvents().stream().anyMatch(e -> e.getType() == AgentEventType.RUN_CANCELLED));
        assertTrue(AgentEventSequenceValidator.validate(allEvents()).isEmpty());
        assertEquals(0, unused.playedCount());
    }

    @Test
    void truncatedArgumentsNeverExecuteTools() {
        AgentModelResponse truncated = AgentModelResponse.success(null,
                List.of(AgentModelToolCall.builder().id("c1").name("echo")
                        .arguments("{\"text\":\"trunc").index(0).build()),
                AgentModelFinishReason.LENGTH,
                AgentModelUsage.builder().promptTokens(1).completionTokens(1).totalTokens(2).build(),
                false, 1);

        AgentLoopResult result = run("m8", "echo", truncated, text("resent"));

        assertEquals(AgentLoopOutcome.COMPLETED, result.getOutcome());
        assertTrue(allEvents().stream().anyMatch(e -> e.getType() == AgentEventType.TOOL_BATCH_REJECTED));
        assertTrue(allEvents().stream().noneMatch(e -> e.getType() == AgentEventType.TOOL_STARTED));
    }

    @Test
    void duplicateRequestIdReturnsTheSameCompletedRun() {
        run("m9", "hello", text("first"));

        long eventsBefore = allEvents().size();
        TestAgentModelClient fresh = new TestAgentModelClient(text("ignored"));
        loop = new DefaultAgentLoop(persistence, fresh, registry,
                new AgentContextAssembler("test", 50), AgentRunConfig.defaults());
        AgentLoopResult duplicate = loop.run(AgentLoopRequest.builder()
                .sessionId("s1")
                .runId("run-m9-again")
                .requestId("m9")
                .actorId("actor")
                .workerId("worker")
                .objectiveId("obj-1")
                .build(), AgentCancellationToken.NEVER_CANCELLED);

        assertEquals(AgentLoopOutcome.COMPLETED, duplicate.getOutcome());
        assertEquals(eventsBefore, allEvents().size());
        assertEquals(0, fresh.playedCount());
    }

    @Test
    void busyWhenAnotherWorkerHoldsTheLease() {
        Instant now = Instant.now();
        assertTrue(persistence.tryAcquireLease("s1", "actor", "other-worker", now, now.plusSeconds(60)));
        appendUserMessage("m10", "hi");
        TestAgentModelClient unused = new TestAgentModelClient(text("unused"));
        loop = new DefaultAgentLoop(persistence, unused, registry,
                new AgentContextAssembler("test", 50), AgentRunConfig.defaults());

        AgentLoopResult result = loop.run(AgentLoopRequest.builder()
                .sessionId("s1")
                .runId("run-m10")
                .requestId("m10")
                .actorId("actor")
                .workerId("worker")
                .objectiveId("obj-1")
                .build(), AgentCancellationToken.NEVER_CANCELLED);

        assertEquals(AgentLoopOutcome.BUSY, result.getOutcome());
        assertEquals("SESSION_BUSY", result.getErrorCode());
    }

    @Test
    void questionToolSuspendsTheRunWaitingForInput() {
        AgentLoopResult result = run("m11", "帮我建数据源",
                toolCall("request_user_input", "{\"question\":\"源库地址是什么？\"}"));

        assertEquals(AgentLoopOutcome.SUSPENDED, result.getOutcome());
        assertEquals(AgentSessionStatus.WAITING_INPUT, result.getSessionStatus());
        assertTrue(allEvents().stream().anyMatch(e -> e.getType() == AgentEventType.QUESTION_REQUIRED));
        assertTrue(allEvents().stream().anyMatch(e -> e.getType() == AgentEventType.RUN_SUSPENDED));
        assertEquals(AgentSessionStatus.WAITING_INPUT, persistence.findSession("s1").orElseThrow().getStatus());
    }

    @Test
    void writeToolWithoutApprovalSuspendsWithApprovalRequired() {
        registry = new AgentToolRegistry(List.of(new EchoTool(), new FakeApprovalTool()));
        AgentLoopResult result = run("m13", "create something",
                toolCall("fake_write", "{\"name\":\"ds-1\"}"));

        assertEquals(AgentLoopOutcome.SUSPENDED, result.getOutcome());
        assertEquals(AgentSessionStatus.WAITING_APPROVAL, result.getSessionStatus());
        assertTrue(allEvents().stream().anyMatch(e -> e.getType() == AgentEventType.APPROVAL_REQUIRED));
        assertTrue(allEvents().stream().anyMatch(e ->
                e.getType() == AgentEventType.APPROVAL_REQUIRED
                        && "ap_fake_1".equals(e.getPayload().path("approvalId").asText())
                        && "digest-fake".equals(e.getPayload().path("actionDigest").asText())));
        assertTrue(allEvents().stream().anyMatch(e -> e.getType() == AgentEventType.RUN_SUSPENDED));
        assertTrue(allEvents().stream().noneMatch(e -> e.getType() == AgentEventType.PLAN_COMMIT_STARTED));
    }

    @Test
    void failedProbeMigratesDraftToTheNewSecretRequest() {
        com.consilens.agent.api.state.AgentDatasourceDraftState draft =
                com.consilens.agent.api.state.AgentDatasourceDraftState.builder()
                        .draftId("draft_source").name("prod").type("mysql")
                        .secretStatus(com.consilens.agent.api.state.AgentSecretStatus.REQUESTED)
                        .secretRequestId("old-sec")
                        .build();
        persistence.saveSnapshot("s1", 0,
                AgentWorkingState.builder()
                        .objectiveId("obj-1").objective("obj").source(draft).build(),
                "init", 1, persistence.findSession("s1").orElseThrow().getVersion());
        registry = new AgentToolRegistry(List.of(new SecretRefillTool()));

        AgentLoopResult result = run("m14", "probe again",
                toolCall("secret_refill", "{\"draftId\":\"draft_source\"}"));

        assertEquals(AgentLoopOutcome.SUSPENDED, result.getOutcome());
        assertEquals(AgentSessionStatus.WAITING_SECRET, result.getSessionStatus());
        assertTrue(allEvents().stream().anyMatch(e ->
                e.getType() == AgentEventType.SECRET_INPUT_REQUIRED
                        && "new-sec-2".equals(e.getPayload().path("secretRequestId").asText())));
        assertTrue(allEvents().stream().anyMatch(e ->
                e.getType() == AgentEventType.SECRET_INPUT_REQUIRED
                        && "password".equals(
                        e.getPayload().path("fields").get(0).asText())));
        AgentWorkingState state = persistence.latestSnapshot("s1").orElseThrow().getWorkingState();
        assertEquals("new-sec-2", state.getSource().getSecretRequestId());
    }

    @Test
    void deadlineExceededTimesOutTheRun() {
        TestAgentModelClient unused = new TestAgentModelClient(text("slow"));
        loop = new DefaultAgentLoop(persistence, unused, registry,
                new AgentContextAssembler("test", 50), AgentRunConfig.defaults());
        appendUserMessage("m12", "hi");

        AgentLoopResult result = loop.run(AgentLoopRequest.builder()
                .sessionId("s1")
                .runId("run-m12")
                .requestId("m12")
                .actorId("actor")
                .workerId("worker")
                .objectiveId("obj-1")
                .deadline(Instant.now().minus(5, ChronoUnit.SECONDS))
                .build(), AgentCancellationToken.NEVER_CANCELLED);

        assertEquals(AgentLoopOutcome.TIMEOUT, result.getOutcome());
        assertEquals(AgentSessionStatus.FAILED, result.getSessionStatus());
        assertTrue(result.isRetryable());
        assertEquals(0, unused.playedCount());
    }
}

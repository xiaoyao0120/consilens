package com.consilens.server.api.controller.ai;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.server.application.ai.AgentConversationApplicationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * SSE event stream. The database is the source of truth: on connect we replay
 * events after afterSeq, then poll for new events (with heartbeats). Emitters
 * are never the persistence layer; reconnect simply resumes from the last seq.
 */
@RestController
@RequestMapping("/v1/ai/sessions/{sessionId}/events")
@ConditionalOnProperty(prefix = "consilens.server.ai", name = "enabled", havingValue = "true")
public class AgentEventStreamController {

    private static final long HEARTBEAT_MILLIS = 15_000;
    private static final long POLL_MILLIS = 1_000;

    private final AgentConversationApplicationService conversationService;
    private final com.consilens.server.application.ai.AgentPrincipalResolver principalResolver;

    public AgentEventStreamController(AgentConversationApplicationService conversationService,
                                      com.consilens.server.application.ai.AgentPrincipalResolver principalResolver) {
        this.conversationService = conversationService;
        this.principalResolver = principalResolver;
    }

    @GetMapping(produces = "text/event-stream")
    public SseEmitter stream(@PathVariable String sessionId,
                             @RequestParam(value = "afterSeq", defaultValue = "-1") long afterSeq) {
        // HTTP 线程先鉴权：未授权请求直接 404，不建立长连接。
        conversationService.getSession(sessionId, principalResolver.resolve().getId());
        SseEmitter emitter = new SseEmitter(0L);
        Thread poller = new Thread(() -> {
            long cursor = afterSeq;
            long lastHeartbeat = System.currentTimeMillis();
            try {
                while (true) {
                    List<AgentEvent> events = conversationService.events(sessionId,
                            principalResolver.resolve().getId(), cursor);
                    for (AgentEvent event : events) {
                        emitter.send(SseEmitter.event()
                                .id(String.valueOf(event.getSeq()))
                                .name(event.getType().name().toLowerCase())
                                .data(event));
                        cursor = event.getSeq();
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeat >= HEARTBEAT_MILLIS) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                        lastHeartbeat = now;
                    }
                    Thread.sleep(POLL_MILLIS);
                }
            } catch (com.consilens.server.domain.exception.ResourceNotFoundException e) {
                // 会话已被删除：SSE 流正常收尾，不报错刷日志
                emitter.complete();
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            } catch (IOException | InterruptedException e) {
                emitter.completeWithError(e);
            }
        }, "ai-sse-" + sessionId);
        poller.setDaemon(true);
        poller.start();
        emitter.onCompletion(poller::interrupt);
        emitter.onTimeout(poller::interrupt);
        return emitter;
    }
}

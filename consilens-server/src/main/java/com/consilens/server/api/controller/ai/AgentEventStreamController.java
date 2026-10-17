package com.consilens.server.api.controller.ai;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.server.application.ai.AgentConversationApplicationService;
import com.consilens.server.application.ai.AgentPrincipalResolver;
import com.consilens.server.boot.ConsilensServerProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSE event stream. The database is the source of truth: on connect we replay
 * events after afterSeq, then poll for new events (with heartbeats). Emitters
 * are never the persistence layer; reconnect simply resumes from the last seq.
 *
 * <p>Each stream occupies one task in a shared, bounded executor. The semaphore
 * caps concurrent streams at {@code consilens.server.ai.sse.max-connections};
 * completion/timeout/error cancels the task and releases the slot so a
 * disconnected client cannot leak a polling thread.
 */
@RestController
@RequestMapping("/v1/ai/sessions/{sessionId}/events")
@ConditionalOnProperty(prefix = "consilens.server.ai", name = "enabled", havingValue = "true")
public class AgentEventStreamController implements DisposableBean {

    private final AgentConversationApplicationService conversationService;
    private final AgentPrincipalResolver principalResolver;
    private final ThreadPoolTaskExecutor streamExecutor;
    private final Semaphore streamSlots;
    private final long heartbeatMillis;
    private final long pollMillis;

    public AgentEventStreamController(AgentConversationApplicationService conversationService,
                                      AgentPrincipalResolver principalResolver,
                                      ConsilensServerProperties properties) {
        this.conversationService = conversationService;
        this.principalResolver = principalResolver;
        ConsilensServerProperties.Ai.Sse sse = properties.getAi().getSse();
        int maxConnections = Math.max(1, sse.getMaxConnections());
        this.heartbeatMillis = Math.max(1L, sse.getHeartbeatMs());
        this.pollMillis = Math.max(1L, sse.getPollMs());
        this.streamSlots = new Semaphore(maxConnections, true);
        // 有界且共享：线程数固定为并发上限，配合信号量保证不会为每个连接新起线程。
        this.streamExecutor = new ThreadPoolTaskExecutor();
        this.streamExecutor.setCorePoolSize(maxConnections);
        this.streamExecutor.setMaxPoolSize(maxConnections);
        this.streamExecutor.setQueueCapacity(0);
        this.streamExecutor.setThreadNamePrefix("ai-sse-");
        this.streamExecutor.setWaitForTasksToCompleteOnShutdown(false);
        this.streamExecutor.initialize();
    }

    @GetMapping(produces = "text/event-stream")
    public SseEmitter stream(@PathVariable String sessionId,
                             @RequestParam(value = "afterSeq", defaultValue = "-1") long afterSeq) {
        // HTTP 线程先鉴权：未授权请求直接 404，不建立长连接。
        String principalId = principalResolver.resolve().getId();
        conversationService.getSession(sessionId, principalId);
        if (!streamSlots.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "too many concurrent AI event streams");
        }
        SseEmitter emitter = new SseEmitter(0L);
        StreamTask task = new StreamTask(sessionId, principalId, afterSeq, emitter);
        task.submit();
        return emitter;
    }

    @Override
    public void destroy() {
        streamExecutor.shutdown();
    }

    /** 一条 SSE 流的生命周期：停止标志、执行任务句柄与信号量释放（幂等）。 */
    private final class StreamTask implements Runnable {

        private final String sessionId;
        private final String principalId;
        private final long afterSeq;
        private final SseEmitter emitter;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean slotReleased = new AtomicBoolean();
        private final AtomicReference<Future<?>> future = new AtomicReference<>();

        private StreamTask(String sessionId, String principalId, long afterSeq, SseEmitter emitter) {
            this.sessionId = sessionId;
            this.principalId = principalId;
            this.afterSeq = afterSeq;
            this.emitter = emitter;
        }

        private void submit() {
            emitter.onCompletion(this::stop);
            emitter.onTimeout(() -> {
                completeQuietly();
                stop();
            });
            emitter.onError(error -> stop());
            Future<?> submitted;
            try {
                submitted = streamExecutor.submit(this);
            } catch (RuntimeException e) {
                // 容器正在关闭等情况下拒绝任务：释放槽位再抛出，避免信号量泄漏。
                stop();
                throw e;
            }
            future.set(submitted);
            if (stopped.get()) {
                // complete/error 可能在 future 赋值前触发，补一次取消。
                submitted.cancel(true);
            }
        }

        @Override
        public void run() {
            long cursor = afterSeq;
            long lastHeartbeat = System.currentTimeMillis();
            try {
                while (!stopped.get()) {
                    List<AgentEvent> events = conversationService.events(sessionId, principalId, cursor);
                    for (AgentEvent event : events) {
                        if (stopped.get()) {
                            return;
                        }
                        emitter.send(SseEmitter.event()
                                .id(String.valueOf(event.getSeq()))
                                .name(event.getType().name().toLowerCase())
                                .data(event));
                        cursor = event.getSeq();
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeat >= heartbeatMillis) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                        lastHeartbeat = now;
                    }
                    Thread.sleep(pollMillis);
                }
            } catch (com.consilens.server.domain.exception.ResourceNotFoundException e) {
                // 会话已被删除：SSE 流正常收尾，不报错刷日志
                completeQuietly();
            } catch (RuntimeException e) {
                completeWithErrorQuietly(e);
            } catch (IOException e) {
                completeWithErrorQuietly(e);
            } catch (InterruptedException e) {
                // 任务被取消（emitter 完成/超时/服务关闭）：恢复中断标志后直接退出。
                Thread.currentThread().interrupt();
            } finally {
                stop();
            }
        }

        /**
         * 停止轮询并释放并发槽位。onCompletion/onTimeout/onError 与任务自身都会调用，
         * 用 CAS 保证信号量只释放一次、任务只取消一次。
         */
        private void stop() {
            stopped.set(true);
            Future<?> current = future.get();
            if (current != null) {
                current.cancel(true);
            }
            if (slotReleased.compareAndSet(false, true)) {
                streamSlots.release();
            }
        }

        private void completeQuietly() {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // 已由容器完成或连接已断开
            }
        }

        private void completeWithErrorQuietly(Throwable error) {
            try {
                emitter.completeWithError(error);
            } catch (RuntimeException ignored) {
                // 已由容器完成或连接已断开
            }
        }
    }
}

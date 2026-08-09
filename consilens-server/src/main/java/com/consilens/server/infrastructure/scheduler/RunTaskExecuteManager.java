package com.consilens.server.infrastructure.scheduler;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.capability.CapabilityExecutionException;
import com.consilens.server.application.capability.ServerCapabilityFacade;
import com.consilens.server.application.topology.ServerTopologyService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.model.TaskCommandRecord;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.model.TaskRecord;
import com.consilens.server.domain.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "consilens.server.scheduler", name = "enabled", havingValue = "true")
public class RunTaskExecuteManager {

    private final TaskRepository taskRepository;
    private final ServerCapabilityFacade serverCapabilityFacade;
    private final ServerTopologyService serverTopologyService;
    private final RunTaskRecoveryService runTaskRecoveryService;
    private final ObjectMapper objectMapper;
    private final ConsilensServerProperties properties;
    private final ThreadPoolExecutor executorService;
    private final ScheduledExecutorService heartbeatScheduler;

    public RunTaskExecuteManager(TaskRepository taskRepository,
                                 ServerCapabilityFacade serverCapabilityFacade,
                                 ServerTopologyService serverTopologyService,
                                 RunTaskRecoveryService runTaskRecoveryService,
                                 ConsilensServerProperties properties,
                                 ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.serverCapabilityFacade = serverCapabilityFacade;
        this.serverTopologyService = serverTopologyService;
        this.runTaskRecoveryService = runTaskRecoveryService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        int executeThreads = Math.max(properties.getScheduler().getExecuteThreads(), 1);
        int queueCapacity = Math.max(properties.getScheduler().getExecuteQueueCapacity(), 1);
        this.executorService = new ThreadPoolExecutor(executeThreads,
                executeThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new SchedulerThreadFactory("run-task-execute-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                new SchedulerThreadFactory("run-task-heartbeat-"));
    }

    @PostConstruct
    public void start() {
        log.info("run task execute manager started");
    }

    @PreDestroy
    public void stop() {
        executorService.shutdownNow();
        heartbeatScheduler.shutdownNow();
    }

    public void addExecuteCommand(TaskCommandRecord command) {
        executorService.submit(() -> dispatch(command));
    }

    private void dispatch(TaskCommandRecord command) {
        TaskRecord task = taskRepository.findById(command.getTaskId()).orElse(null);
        if (task == null) {
            return;
        }

        Instant startTime = Instant.now();
        try {
            if (!taskRepository.updateRunning(task.getId(), serverTopologyService.currentNodeKey(), startTime)) {
                return;
            }
            ScheduledFuture<?> heartbeat = startHeartbeat(task);
            RunRequest runRequest = objectMapper.readValue(task.getRequestPayload(), RunRequest.class);
            try {
                ArtifactRefDto artifact = serverCapabilityFacade.run(runRequest, TaskExecutionContext.builder()
                        .taskKey(task.getTaskKey())
                        .taskId(task.getId())
                        .traceId(task.getTraceId())
                        .nodeKey(serverTopologyService.currentNodeKey())
                        .startTime(startTime)
                        .build());
                taskRepository.updateSuccess(task.getId(), artifact.getId(), Instant.now());
            } finally {
                heartbeat.cancel(false);
            }
        } catch (CapabilityExecutionException exception) {
            if (exception.isRetryable()) {
                runTaskRecoveryService.retryOrFail(task,
                        exception.getErrorCode(),
                        exception.getMessage(),
                        Instant.now());
            } else {
                taskRepository.updateFailure(task.getId(),
                        exception.getErrorCode(),
                        exception.getMessage(),
                        Instant.now());
            }
        } catch (Exception exception) {
            log.warn("Failed to dispatch run task {}", task.getTaskKey(), exception);
            runTaskRecoveryService.retryOrFail(task, "RUN_DISPATCH_ERROR", exception.getMessage(), Instant.now());
        }
    }

    /**
     * Renew the task start time periodically so the recovery guard treats a
     * long-running comparison as alive and only reclaims tasks whose heartbeat
     * actually stopped.
     */
    private ScheduledFuture<?> startHeartbeat(TaskRecord task) {
        long leaseSeconds = Math.max(properties.getScheduler().getClaimLeaseSeconds(), 1L);
        long heartbeatIntervalSeconds = Math.max(leaseSeconds / 3, 1L);
        return heartbeatScheduler.scheduleAtFixedRate(
                () -> taskRepository.renewRunning(task.getId(), Instant.now()),
                heartbeatIntervalSeconds,
                heartbeatIntervalSeconds,
                TimeUnit.SECONDS);
    }
}

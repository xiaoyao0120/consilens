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
    private final ThreadPoolExecutor executorService;

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
        int executeThreads = Math.max(properties.getScheduler().getExecuteThreads(), 1);
        int queueCapacity = Math.max(properties.getScheduler().getExecuteQueueCapacity(), 1);
        this.executorService = new ThreadPoolExecutor(executeThreads,
                executeThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new SchedulerThreadFactory("run-task-execute-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct
    public void start() {
        log.info("run task execute manager started");
    }

    @PreDestroy
    public void stop() {
        executorService.shutdownNow();
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
            RunRequest runRequest = objectMapper.readValue(task.getRequestPayload(), RunRequest.class);
            ArtifactRefDto artifact = serverCapabilityFacade.run(runRequest, TaskExecutionContext.builder()
                    .taskKey(task.getTaskKey())
                    .taskId(task.getId())
                    .traceId(task.getTraceId())
                    .nodeKey(serverTopologyService.currentNodeKey())
                    .startTime(startTime)
                    .build());
            taskRepository.updateSuccess(task.getId(), artifact.getId(), Instant.now());
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
}

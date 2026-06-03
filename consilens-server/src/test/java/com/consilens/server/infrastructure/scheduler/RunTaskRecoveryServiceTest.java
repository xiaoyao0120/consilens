package com.consilens.server.infrastructure.scheduler;

import com.consilens.server.application.task.RunTaskCommandEnqueueService;
import com.consilens.server.application.topology.ServerNodeQueryService;
import com.consilens.server.application.topology.ServerTopologyService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enumtype.TaskStatus;
import com.consilens.server.domain.model.TaskCommandRecord;
import com.consilens.server.domain.model.TaskRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import com.consilens.server.domain.repository.TaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunTaskRecoveryServiceTest {

    @Test
    void shouldRequeueTaskAfterExpiredClaimReleased() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        RunTaskCommandEnqueueService runTaskCommandEnqueueService = mock(RunTaskCommandEnqueueService.class);
        ServerNodeQueryService serverNodeQueryService = mock(ServerNodeQueryService.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        Instant now = Instant.now();
        TaskRecord task = TaskRecord.builder()
                .id(100L)
                .taskKey("task-requeue")
                .status(TaskStatus.CLAIMED)
                .build();
        TaskCommandRecord command = TaskCommandRecord.builder()
                .id(200L)
                .taskId(100L)
                .build();

        when(taskCommandRepository.listExpiredClaims(now, properties.getScheduler().getRecoveryBatchSize()))
                .thenReturn(List.of(command));
        when(taskCommandRepository.release(200L, now)).thenReturn(true);
        when(taskRepository.releaseClaimed(100L, now)).thenReturn(true);
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));

        RunTaskRecoveryService recoveryService = new RunTaskRecoveryService(taskRepository,
                taskCommandRepository,
                runTaskCommandEnqueueService,
                serverNodeQueryService,
                serverTopologyService,
                properties);

        recoveryService.recoverExpiredClaims(now);

        verify(taskCommandRepository).release(200L, now);
        verify(taskRepository).releaseClaimed(100L, now);
        verify(runTaskCommandEnqueueService).enqueue(eq(task), eq(now));
    }

    @Test
    void shouldResetAndRequeueRetryableRunningTask() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        RunTaskCommandEnqueueService runTaskCommandEnqueueService = mock(RunTaskCommandEnqueueService.class);
        ServerNodeQueryService serverNodeQueryService = mock(ServerNodeQueryService.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        Instant now = Instant.now();
        TaskRecord task = TaskRecord.builder()
                .id(101L)
                .taskKey("task-retry")
                .status(TaskStatus.RUNNING)
                .retryCount(0)
                .maxRetryCount(3)
                .build();

        when(taskRepository.updateRetryableFromStatuses(eq(101L), any(), eq("TEMPORARY_ERROR"), eq("temporary"), eq(now)))
                .thenReturn(true);
        when(taskRepository.resetForRetry(eq(101L), any(), eq(now))).thenReturn(true);

        RunTaskRecoveryService recoveryService = new RunTaskRecoveryService(taskRepository,
                taskCommandRepository,
                runTaskCommandEnqueueService,
                serverNodeQueryService,
                serverTopologyService,
                properties);

        recoveryService.retryOrFail(task, "TEMPORARY_ERROR", "temporary", now);

        verify(taskCommandRepository).releaseClaimedByTaskId(101L, now);
        verify(taskRepository).resetForRetry(eq(101L), any(), eq(now));
        verify(runTaskCommandEnqueueService).enqueue(eq(task), eq(now));
    }

    @Test
    void shouldRecoverDeadNodeTasksEvenWhenNoAliveNodesAreReported() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        RunTaskCommandEnqueueService runTaskCommandEnqueueService = mock(RunTaskCommandEnqueueService.class);
        ServerNodeQueryService serverNodeQueryService = mock(ServerNodeQueryService.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        Instant now = Instant.now();
        TaskRecord task = TaskRecord.builder()
                .id(102L)
                .taskKey("task-dead-node")
                .status(TaskStatus.RUNNING)
                .retryCount(0)
                .maxRetryCount(3)
                .build();

        when(serverNodeQueryService.listAliveNodes()).thenReturn(List.of());
        when(taskRepository.listByStatusesExcludingExecuteNodes(any(), any(), eq(properties.getScheduler().getRecoveryBatchSize())))
                .thenReturn(List.of(task));
        when(taskRepository.updateRetryableFromStatuses(eq(102L), any(), eq("EXECUTE_NODE_LOST"), eq("Execution node heartbeat expired"), eq(now)))
                .thenReturn(true);
        when(taskRepository.resetForRetry(eq(102L), any(), eq(now))).thenReturn(true);

        RunTaskRecoveryService recoveryService = new RunTaskRecoveryService(taskRepository,
                taskCommandRepository,
                runTaskCommandEnqueueService,
                serverNodeQueryService,
                serverTopologyService,
                properties);

        recoveryService.recoverTasksOnDeadNodes(now);

        verify(taskCommandRepository).releaseClaimedByTaskId(102L, now);
        verify(taskRepository).resetForRetry(eq(102L), any(), eq(now));
        verify(runTaskCommandEnqueueService).enqueue(eq(task), eq(now));
        verify(taskRepository, never()).listByStatusesExcludingExecuteNodes(any(), any(), eq(0));
    }
}

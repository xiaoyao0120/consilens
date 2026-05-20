package com.consilens.server.infrastructure.scheduler;

import com.consilens.server.application.topology.ServerTopologyService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enumtype.TaskStatus;
import com.consilens.server.domain.model.ServerTopologySnapshot;
import com.consilens.server.domain.model.TaskCommandRecord;
import com.consilens.server.domain.model.TaskRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import com.consilens.server.domain.repository.TaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobSchedulerTest {

    @Test
    void shouldClaimSubmitAndCompleteCommand() throws Exception {
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        ServerTopologyService topologyService = mock(ServerTopologyService.class);
        RunTaskExecuteManager runTaskExecuteManager = mock(RunTaskExecuteManager.class);
        RunTaskRecoveryService runTaskRecoveryService = mock(RunTaskRecoveryService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getScheduler().setCommandPollIntervalMs(10);
        properties.getScheduler().setClaimLeaseSeconds(15);

        TaskCommandRecord command = TaskCommandRecord.builder()
                .id(100L)
                .commandKey("cmd-test")
                .taskId(200L)
                .build();
        when(topologyService.snapshot()).thenReturn(ServerTopologySnapshot.builder()
                .totalSlot(4)
                .currentSlot(2)
                .dispatchable(true)
                .build());
        when(topologyService.currentNodeKey()).thenReturn("node-a");
        when(taskCommandRepository.getStartCommand(eq(4), eq(2), any(Instant.class)))
                .thenReturn(command)
                .thenReturn(null);
        when(taskRepository.findById(200L)).thenReturn(Optional.of(TaskRecord.builder()
                .id(200L)
                .status(TaskStatus.PENDING)
                .build()));
        when(taskCommandRepository.claim(eq(100L), eq("node-a"), any(Instant.class), any(Instant.class)))
                .thenReturn(true);
        when(taskRepository.updateClaimed(eq(200L), eq("node-a"), any(Instant.class))).thenReturn(true);

        JobScheduler scheduler = new JobScheduler(taskCommandRepository,
                taskRepository,
                topologyService,
                runTaskExecuteManager,
                runTaskRecoveryService,
                properties);
        try {
            scheduler.startScheduler();

            verify(runTaskExecuteManager, timeout(1000)).addExecuteCommand(command);
            verify(taskRepository, timeout(1000)).updateClaimed(eq(200L), eq("node-a"), any(Instant.class));
            verify(taskCommandRepository, timeout(1000)).markDone(eq(100L), any(Instant.class));
            verify(runTaskRecoveryService, timeout(1000)).recoverCurrentNodeStartupTasks(any(Instant.class));
            verify(runTaskRecoveryService, timeout(1000).atLeastOnce()).recoverExpiredClaims(any(Instant.class));
            verify(runTaskRecoveryService, timeout(1000).atLeastOnce()).recoverTasksOnDeadNodes(any(Instant.class));
        } finally {
            scheduler.stopScheduler();
            scheduler.join(1000);
        }
    }
}

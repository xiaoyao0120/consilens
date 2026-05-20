package com.consilens.server.infrastructure.scheduler;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.capability.CapabilityExecutionException;
import com.consilens.server.application.capability.ServerCapabilityFacade;
import com.consilens.server.application.topology.ServerTopologyService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.model.TaskCommandRecord;
import com.consilens.server.domain.model.TaskRecord;
import com.consilens.server.domain.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class RunTaskExecuteManagerTest {

    @Test
    void shouldExecuteRunTaskAndUpdateSuccess() throws Exception {
        TaskRepository taskRepository = mock(TaskRepository.class);
        ServerCapabilityFacade serverCapabilityFacade = mock(ServerCapabilityFacade.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        RunTaskRecoveryService runTaskRecoveryService = mock(RunTaskRecoveryService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        ObjectMapper objectMapper = new ObjectMapper();

        RunRequest request = new RunRequest();
        request.setSerialNo("serial-test");
        request.setConfigArtifactId("artifact-config");
        TaskRecord task = TaskRecord.builder()
                .id(10L)
                .taskKey("task-test")
                .traceId("trace-test")
                .requestPayload(objectMapper.writeValueAsString(request))
                .build();
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(serverTopologyService.currentNodeKey()).thenReturn("node-a");
        when(taskRepository.updateRunning(eq(10L), eq("node-a"), any(Instant.class))).thenReturn(true);
        when(serverCapabilityFacade.run(any(RunRequest.class), any())).thenReturn(ArtifactRefDto.builder()
                .id("artifact-result")
                .type("RUN_RESULT")
                .format("json")
                .build());

        RunTaskExecuteManager manager = new RunTaskExecuteManager(taskRepository,
                serverCapabilityFacade,
                serverTopologyService,
                runTaskRecoveryService,
                properties,
                objectMapper);
        try {
            manager.addExecuteCommand(TaskCommandRecord.builder()
                    .id(20L)
                    .taskId(10L)
                    .commandKey("cmd-test")
                    .build());

            verify(taskRepository, timeout(1000)).updateRunning(eq(10L), eq("node-a"), any(Instant.class));
            verify(taskRepository, timeout(1000)).updateSuccess(eq(10L), eq("artifact-result"), any(Instant.class));
        } finally {
            manager.stop();
        }
    }

    @Test
    void shouldRecoverRetryableExecutionFailure() throws Exception {
        TaskRepository taskRepository = mock(TaskRepository.class);
        ServerCapabilityFacade serverCapabilityFacade = mock(ServerCapabilityFacade.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        RunTaskRecoveryService runTaskRecoveryService = mock(RunTaskRecoveryService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        ObjectMapper objectMapper = new ObjectMapper();

        RunRequest request = new RunRequest();
        request.setSerialNo("serial-retry");
        request.setConfigArtifactId("artifact-config");
        TaskRecord task = TaskRecord.builder()
                .id(11L)
                .taskKey("task-retry")
                .traceId("trace-retry")
                .requestPayload(objectMapper.writeValueAsString(request))
                .retryCount(0)
                .maxRetryCount(3)
                .build();
        when(taskRepository.findById(11L)).thenReturn(Optional.of(task));
        when(serverTopologyService.currentNodeKey()).thenReturn("node-a");
        when(taskRepository.updateRunning(eq(11L), eq("node-a"), any(Instant.class))).thenReturn(true);
        when(serverCapabilityFacade.run(any(RunRequest.class), any())).thenThrow(
                new CapabilityExecutionException("TEMPORARY_ERROR",
                        "temporary failure",
                        null,
                        true,
                        null));

        RunTaskExecuteManager manager = new RunTaskExecuteManager(taskRepository,
                serverCapabilityFacade,
                serverTopologyService,
                runTaskRecoveryService,
                properties,
                objectMapper);
        try {
            manager.addExecuteCommand(TaskCommandRecord.builder()
                    .id(21L)
                    .taskId(11L)
                    .commandKey("cmd-retry")
                    .build());

            verify(runTaskRecoveryService, timeout(1000))
                    .retryOrFail(eq(task), eq("TEMPORARY_ERROR"), eq("temporary failure"), any(Instant.class));
            verify(taskRepository, never()).updateFailure(eq(11L), any(), any(), any());
        } finally {
            manager.stop();
        }
    }
}

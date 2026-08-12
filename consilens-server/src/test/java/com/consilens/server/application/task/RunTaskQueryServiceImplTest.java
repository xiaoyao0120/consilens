package com.consilens.server.application.task;

import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.application.task.impl.RunTaskQueryServiceImpl;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.domain.repository.TaskDefinitionRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunTaskQueryServiceImplTest {

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
    private final ArtifactContentStore contentStore = mock(ArtifactContentStore.class);

    private RunTaskQueryServiceImpl service() {
        return new RunTaskQueryServiceImpl(taskRepository, artifactRepository, contentStore,
                mock(TaskDefinitionRepository.class), new ObjectMapper());
    }

    @Test
    void shouldResolveTaskByNumericId() {
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(123456789012345678L)
                .instanceKey("inst_1")
                .serialNo("serial-1")
                .status(TaskStatus.SUCCEEDED)
                .traceId("trace-1")
                .build();
        when(taskRepository.resolveByRef("123456789012345678")).thenReturn(Optional.of(task));
        when(artifactRepository.listByTaskId(123456789012345678L)).thenReturn(List.of());

        TaskQueryResponse response = service().getTask("123456789012345678", "trace-x");

        assertEquals("inst_1", response.getTaskId());
        assertEquals("serial-1", response.getSerialNo());
    }

    @Test
    void shouldExposeFullTaskFieldsInGetTask() {
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(1L)
                .instanceKey("task-1")
                .serialNo("serial-1")
                .status(TaskStatus.SUCCEEDED)
                .traceId("trace-1")
                .executeNodeKey("node-1")
                .retryCount(2)
                .submitTime(Instant.parse("2026-01-01T00:00:00Z"))
                .startTime(Instant.parse("2026-01-01T00:00:01Z"))
                .endTime(Instant.parse("2026-01-01T00:00:02Z"))
                .build();
        when(taskRepository.resolveByRef("task-1")).thenReturn(Optional.of(task));
        when(artifactRepository.listByTaskId(1L)).thenReturn(List.of());

        TaskQueryResponse response = service().getTask("task-1", "trace-x");

        assertEquals("task-1", response.getTaskId());
        assertEquals("serial-1", response.getSerialNo());
        assertEquals("SUCCEEDED", response.getStatus());
        assertEquals("trace-1", response.getTraceId());
        assertEquals("node-1", response.getExecuteNodeKey());
        assertEquals(2, response.getRetryCount());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), response.getSubmitTime());
        assertEquals(Instant.parse("2026-01-01T00:00:01Z"), response.getStartTime());
        assertEquals(Instant.parse("2026-01-01T00:00:02Z"), response.getEndTime());
    }
}

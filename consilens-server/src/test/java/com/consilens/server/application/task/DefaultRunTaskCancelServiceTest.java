package com.consilens.server.application.task;

import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.TaskRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import com.consilens.server.domain.repository.TaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultRunTaskCancelServiceTest {

    @Test
    void shouldRequestCancellationForRunningTaskWithoutClaimingItStopped() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        TaskRecord task = TaskRecord.builder()
                .id(10L)
                .taskKey("task-running")
                .status(TaskStatus.RUNNING)
                .build();
        when(taskRepository.findByTaskKey("task-running")).thenReturn(Optional.of(task));
        when(taskRepository.requestCancellation(eq(10L), eq("trace-1"), any(Instant.class))).thenReturn(true);

        TaskStatus status = new DefaultRunTaskCancelService(taskRepository, taskCommandRepository)
                .cancel("task-running", "trace-1");

        assertThat(status).isEqualTo(TaskStatus.CANCEL_REQUESTED);
        verify(taskRepository).requestCancellation(eq(10L), eq("trace-1"), any(Instant.class));
        verify(taskRepository, never()).cancel(eq(10L), eq("trace-1"), any(Instant.class));
        verify(taskCommandRepository, never()).releaseOpenByTaskId(any(), any());
    }
}

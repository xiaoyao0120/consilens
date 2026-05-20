package com.consilens.server.application.task;

import com.consilens.server.domain.enumtype.TaskStatus;
import com.consilens.server.domain.exception.ConflictException;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.TaskRecord;
import com.consilens.server.domain.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class DefaultRunTaskRetryService implements RunTaskRetryService {

    private final TaskRepository taskRepository;
    private final RunTaskCommandEnqueueService runTaskCommandEnqueueService;

    public DefaultRunTaskRetryService(TaskRepository taskRepository,
                                      RunTaskCommandEnqueueService runTaskCommandEnqueueService) {
        this.taskRepository = taskRepository;
        this.runTaskCommandEnqueueService = runTaskCommandEnqueueService;
    }

    @Override
    @Transactional
    public void retry(String taskId, String traceId) {
        TaskRecord task = taskRepository.findByTaskKey(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        if (task.getStatus() != TaskStatus.FAILED
                && task.getStatus() != TaskStatus.RETRYABLE
                && task.getStatus() != TaskStatus.CANCELLED) {
            throw new ConflictException("Task is not retryable in status " + task.getStatus());
        }
        Instant now = Instant.now();
        if (!taskRepository.resetForRetry(task.getId(), traceId, now)) {
            throw new ConflictException("Task is not retryable in status " + task.getStatus());
        }
        runTaskCommandEnqueueService.enqueue(task, now);
    }
}

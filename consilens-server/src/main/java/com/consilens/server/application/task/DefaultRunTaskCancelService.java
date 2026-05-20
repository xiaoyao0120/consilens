package com.consilens.server.application.task;

import com.consilens.server.domain.enumtype.TaskStatus;
import com.consilens.server.domain.exception.ConflictException;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.TaskRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import com.consilens.server.domain.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class DefaultRunTaskCancelService implements RunTaskCancelService {

    private final TaskRepository taskRepository;
    private final TaskCommandRepository taskCommandRepository;

    public DefaultRunTaskCancelService(TaskRepository taskRepository,
                                       TaskCommandRepository taskCommandRepository) {
        this.taskRepository = taskRepository;
        this.taskCommandRepository = taskCommandRepository;
    }

    @Override
    @Transactional
    public void cancel(String taskId, String traceId) {
        TaskRecord task = taskRepository.findByTaskKey(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.CLAIMED) {
            throw new ConflictException("Task can only be cancelled from PENDING or CLAIMED");
        }
        Instant now = Instant.now();
        if (!taskRepository.cancel(task.getId(), traceId, now)) {
            throw new ConflictException("Task can only be cancelled from PENDING or CLAIMED");
        }
        taskCommandRepository.releaseOpenByTaskId(task.getId(), now);
    }
}

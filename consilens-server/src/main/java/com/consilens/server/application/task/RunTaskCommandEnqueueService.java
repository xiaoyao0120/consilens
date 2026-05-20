package com.consilens.server.application.task;

import com.consilens.server.domain.enumtype.TaskCommandStatus;
import com.consilens.server.domain.model.TaskCommandRecord;
import com.consilens.server.domain.model.TaskRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class RunTaskCommandEnqueueService {

    private final TaskCommandRepository taskCommandRepository;

    public RunTaskCommandEnqueueService(TaskCommandRepository taskCommandRepository) {
        this.taskCommandRepository = taskCommandRepository;
    }

    public TaskCommandRecord enqueue(TaskRecord task, Instant scheduleTime) {
        TaskCommandRecord command = TaskCommandRecord.builder()
                .commandKey("cmd_" + UUID.randomUUID())
                .taskId(task.getId())
                .shardKey(task.getTaskKey())
                .shardSlot(shardSlot(task.getTaskKey()))
                .status(TaskCommandStatus.PENDING)
                .scheduleTime(scheduleTime)
                .createdAt(scheduleTime)
                .updatedAt(scheduleTime)
                .build();
        return taskCommandRepository.save(command);
    }

    private int shardSlot(String taskKey) {
        return Math.floorMod(taskKey.hashCode(), Integer.MAX_VALUE);
    }
}

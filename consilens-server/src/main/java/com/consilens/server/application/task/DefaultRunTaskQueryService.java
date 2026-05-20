package com.consilens.server.application.task;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.enumtype.TaskStatus;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.model.TaskRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.domain.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class DefaultRunTaskQueryService implements RunTaskQueryService {

    private final TaskRepository taskRepository;
    private final ArtifactRepository artifactRepository;

    public DefaultRunTaskQueryService(TaskRepository taskRepository, ArtifactRepository artifactRepository) {
        this.taskRepository = taskRepository;
        this.artifactRepository = artifactRepository;
    }

    @Override
    public TaskQueryResponse getTask(String taskId, String traceId) {
        TaskRecord task = taskRepository.findByTaskKey(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        return TaskQueryResponse.builder()
                .taskId(task.getTaskKey())
                .taskType("RUN")
                .status(task.getStatus().name())
                .traceId(task.getTraceId())
                .executeNodeKey(task.getExecuteNodeKey())
                .artifacts(artifactRepository.listByTaskId(task.getId()).stream()
                        .map(this::toArtifactRef)
                        .collect(Collectors.toList()))
                .availableNextActions(nextActions(task))
                .build();
    }

    private ArtifactRefDto toArtifactRef(ArtifactRecord record) {
        return ArtifactRefDto.builder()
                .id(record.getId())
                .type(record.getArtifactType().name())
                .format(record.getArtifactFormat())
                .build();
    }

    private List<String> nextActions(TaskRecord task) {
        if (task.getStatus() == TaskStatus.SUCCEEDED) {
            return List.of("diagnose", "repair");
        }
        if (task.getStatus() == TaskStatus.FAILED
                || task.getStatus() == TaskStatus.RETRYABLE
                || task.getStatus() == TaskStatus.CANCELLED) {
            return List.of("retry");
        }
        if (task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.CLAIMED) {
            return List.of("cancel");
        }
        return List.of();
    }
}

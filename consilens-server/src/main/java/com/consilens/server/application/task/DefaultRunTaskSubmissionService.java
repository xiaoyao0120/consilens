package com.consilens.server.application.task;

import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.exception.ConflictException;
import com.consilens.server.domain.model.TaskRecord;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.support.hash.Sha256Support;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class DefaultRunTaskSubmissionService implements RunTaskSubmissionService {

    private final TaskRepository taskRepository;
    private final RunTaskCommandEnqueueService runTaskCommandEnqueueService;
    private final ConsilensServerProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public DefaultRunTaskSubmissionService(TaskRepository taskRepository,
                                           RunTaskCommandEnqueueService runTaskCommandEnqueueService,
                                           ConsilensServerProperties properties,
                                           ObjectMapper objectMapper,
                                           TransactionTemplate transactionTemplate) {
        this.taskRepository = taskRepository;
        this.runTaskCommandEnqueueService = runTaskCommandEnqueueService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public TaskAcceptedResponse submit(RunRequest request, String traceId) {
        String requestPayload = toJson(request);
        String requestHash = Sha256Support.hex(requestPayload);
        try {
            return transactionTemplate.execute(status -> submitInTransaction(request,
                    traceId,
                    requestPayload,
                    requestHash));
        } catch (DataIntegrityViolationException exception) {
            return recoverConcurrentSubmit(request, requestHash, exception);
        }
    }

    private TaskAcceptedResponse submitInTransaction(RunRequest request,
                                                     String traceId,
                                                     String requestPayload,
                                                     String requestHash) {
        Optional<TaskRecord> existing = taskRepository.lockBySerialNo(request.getSerialNo());
        if (existing.isPresent()) {
            return acceptExisting(requestHash, existing.get());
        }
        Instant now = Instant.now();
        TaskRecord task = TaskRecord.builder()
                .taskKey("task_" + UUID.randomUUID())
                .serialNo(request.getSerialNo())
                .traceId(traceId)
                .requestPayload(requestPayload)
                .requestHash(requestHash)
                .status(TaskStatus.PENDING)
                .priority(5)
                .scheduleTime(now)
                .submitTime(now)
                .retryCount(0)
                .maxRetryCount(properties.getScheduler().getMaxRetryCount())
                .createdAt(now)
                .updatedAt(now)
                .build();
        TaskRecord savedTask = taskRepository.save(task);

        runTaskCommandEnqueueService.enqueue(savedTask, now);
        return accepted(savedTask);
    }

    private TaskAcceptedResponse recoverConcurrentSubmit(RunRequest request,
                                                         String requestHash,
                                                         DataIntegrityViolationException exception) {
        return taskRepository.findBySerialNo(request.getSerialNo())
                .map(task -> acceptExisting(requestHash, task))
                .orElseThrow(() -> exception);
    }

    private TaskAcceptedResponse acceptExisting(String requestHash, TaskRecord task) {
        if (!requestHash.equals(task.getRequestHash())) {
            throw new ConflictException("serialNo already exists with different request payload",
                    "SERIAL_NO_CONFLICT");
        }
        return accepted(task);
    }

    private TaskAcceptedResponse accepted(TaskRecord task) {
        return TaskAcceptedResponse.builder()
                .taskId(task.getTaskKey())
                .taskType("RUN")
                .status(task.getStatus().name())
                .build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize run request", exception);
        }
    }
}

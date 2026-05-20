package com.consilens.server.infrastructure.db.repository;

import com.consilens.server.domain.enumtype.TaskStatus;
import com.consilens.server.domain.model.TaskRecord;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.db.entity.TaskEntity;
import com.consilens.server.infrastructure.db.service.TaskService;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class MybatisPlusTaskRepository implements TaskRepository {

    private final TaskService taskService;

    public MybatisPlusTaskRepository(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public Optional<TaskRecord> findById(Long id) {
        return Optional.ofNullable(taskService.getById(id)).map(this::toRecord);
    }

    @Override
    public Optional<TaskRecord> findByTaskKey(String taskKey) {
        return Optional.ofNullable(taskService.getByTaskKey(taskKey)).map(this::toRecord);
    }

    @Override
    public Optional<TaskRecord> findBySerialNo(String serialNo) {
        return Optional.ofNullable(taskService.getBySerialNo(serialNo)).map(this::toRecord);
    }

    @Override
    public Optional<TaskRecord> lockBySerialNo(String serialNo) {
        return Optional.ofNullable(taskService.lockBySerialNo(serialNo)).map(this::toRecord);
    }

    @Override
    public TaskRecord save(TaskRecord taskRecord) {
        TaskEntity entity = toEntity(taskRecord);
        taskService.save(entity);
        return toRecord(entity);
    }

    @Override
    public boolean updateClaimed(Long taskId, String executeNodeKey, Instant now) {
        return taskService.updateClaimed(taskId, executeNodeKey, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean updateRunning(Long taskId, String executeNodeKey, Instant now) {
        return taskService.updateRunning(taskId, executeNodeKey, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean updateSuccess(Long taskId, String artifactId, Instant now) {
        return taskService.updateSuccess(taskId, artifactId, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean updateFailure(Long taskId, String errorCode, String errorMessage, Instant now) {
        return taskService.updateFailure(taskId,
                TaskStatus.FAILED,
                errorCode,
                errorMessage,
                DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean updateRetryable(Long taskId, String errorCode, String errorMessage, Instant now) {
        return taskService.updateFailure(taskId,
                TaskStatus.RETRYABLE,
                errorCode,
                errorMessage,
                DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean updateFailureFromStatuses(Long taskId,
                                             Collection<TaskStatus> statuses,
                                             String errorCode,
                                             String errorMessage,
                                             Instant now) {
        return taskService.updateFailureFromStatuses(taskId,
                statuses,
                TaskStatus.FAILED,
                errorCode,
                errorMessage,
                DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean updateRetryableFromStatuses(Long taskId,
                                               Collection<TaskStatus> statuses,
                                               String errorCode,
                                               String errorMessage,
                                               Instant now) {
        return taskService.updateFailureFromStatuses(taskId,
                statuses,
                TaskStatus.RETRYABLE,
                errorCode,
                errorMessage,
                DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean releaseClaimed(Long taskId, Instant now) {
        return taskService.releaseClaimed(taskId, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean resetForRetry(Long taskId, String traceId, Instant now) {
        return taskService.resetForRetry(taskId, traceId, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean cancel(Long taskId, String traceId, Instant now) {
        return taskService.cancel(taskId, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public List<TaskRecord> listByExecuteNodeAndStatuses(String executeNodeKey,
                                                         Collection<TaskStatus> statuses,
                                                         int limit) {
        return taskService.listByExecuteNodeAndStatuses(executeNodeKey, statuses, limit).stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public List<TaskRecord> listByStatusesExcludingExecuteNodes(Collection<TaskStatus> statuses,
                                                                Set<String> executeNodeKeys,
                                                                int limit) {
        return taskService.listByStatusesExcludingExecuteNodes(statuses, executeNodeKeys, limit).stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    private TaskEntity toEntity(TaskRecord record) {
        TaskEntity entity = new TaskEntity();
        entity.setId(record.getId());
        entity.setTaskKey(record.getTaskKey());
        entity.setSerialNo(record.getSerialNo());
        entity.setTenantId(record.getTenantId());
        entity.setTraceId(record.getTraceId());
        entity.setCorrelationId(record.getCorrelationId());
        entity.setRequestPayload(record.getRequestPayload());
        entity.setRequestHash(record.getRequestHash());
        entity.setStatus(record.getStatus());
        entity.setPriority(record.getPriority());
        entity.setScheduleTime(DbTimeSupport.toLocalDateTime(record.getScheduleTime()));
        entity.setSubmitTime(DbTimeSupport.toLocalDateTime(record.getSubmitTime()));
        entity.setStartTime(DbTimeSupport.toLocalDateTime(record.getStartTime()));
        entity.setEndTime(DbTimeSupport.toLocalDateTime(record.getEndTime()));
        entity.setExecuteNodeKey(record.getExecuteNodeKey());
        entity.setResultArtifactId(record.getResultArtifactId());
        entity.setErrorCode(record.getErrorCode());
        entity.setErrorMessage(record.getErrorMessage());
        entity.setRetryCount(record.getRetryCount());
        entity.setMaxRetryCount(record.getMaxRetryCount());
        entity.setCreatedAt(DbTimeSupport.toLocalDateTime(record.getCreatedAt()));
        entity.setUpdatedAt(DbTimeSupport.toLocalDateTime(record.getUpdatedAt()));
        return entity;
    }

    private TaskRecord toRecord(TaskEntity entity) {
        if (entity == null) {
            return null;
        }
        return TaskRecord.builder()
                .id(entity.getId())
                .taskKey(entity.getTaskKey())
                .serialNo(entity.getSerialNo())
                .tenantId(entity.getTenantId())
                .traceId(entity.getTraceId())
                .correlationId(entity.getCorrelationId())
                .requestPayload(entity.getRequestPayload())
                .requestHash(entity.getRequestHash())
                .status(entity.getStatus())
                .priority(entity.getPriority())
                .scheduleTime(DbTimeSupport.toInstant(entity.getScheduleTime()))
                .submitTime(DbTimeSupport.toInstant(entity.getSubmitTime()))
                .startTime(DbTimeSupport.toInstant(entity.getStartTime()))
                .endTime(DbTimeSupport.toInstant(entity.getEndTime()))
                .executeNodeKey(entity.getExecuteNodeKey())
                .resultArtifactId(entity.getResultArtifactId())
                .errorCode(entity.getErrorCode())
                .errorMessage(entity.getErrorMessage())
                .retryCount(entity.getRetryCount())
                .maxRetryCount(entity.getMaxRetryCount())
                .createdAt(DbTimeSupport.toInstant(entity.getCreatedAt()))
                .updatedAt(DbTimeSupport.toInstant(entity.getUpdatedAt()))
                .build();
    }
}

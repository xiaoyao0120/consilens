package com.consilens.server.infrastructure.db.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.infrastructure.db.entity.TaskEntity;
import com.consilens.server.infrastructure.db.mapper.TaskMapper;
import com.consilens.server.infrastructure.db.service.TaskService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Service
public class TaskServiceImpl extends ServiceImpl<TaskMapper, TaskEntity> implements TaskService {

    @Override
    public TaskEntity getByTaskKey(String taskKey) {
        return getOne(new QueryWrapper<TaskEntity>().lambda()
                .eq(TaskEntity::getTaskKey, taskKey), false);
    }

    @Override
    public TaskEntity getBySerialNo(String serialNo) {
        return getOne(new QueryWrapper<TaskEntity>().lambda()
                .eq(TaskEntity::getSerialNo, serialNo), false);
    }

    @Override
    public TaskEntity lockBySerialNo(String serialNo) {
        return baseMapper.lockBySerialNo(serialNo);
    }

    @Override
    public boolean updateStatus(Long taskId, TaskStatus status, LocalDateTime now) {
        return update(new UpdateWrapper<TaskEntity>().lambda()
                .set(TaskEntity::getStatus, status)
                .set(TaskEntity::getUpdatedAt, now)
                .eq(TaskEntity::getId, taskId));
    }

    @Override
    public boolean updateClaimed(Long taskId, String executeNodeKey, LocalDateTime now) {
        return update(new UpdateWrapper<TaskEntity>().lambda()
                .set(TaskEntity::getStatus, TaskStatus.CLAIMED)
                .set(TaskEntity::getExecuteNodeKey, executeNodeKey)
                .set(TaskEntity::getUpdatedAt, now)
                .eq(TaskEntity::getId, taskId)
                .eq(TaskEntity::getStatus, TaskStatus.PENDING));
    }

    @Override
    public boolean updateRunning(Long taskId, String executeNodeKey, LocalDateTime now) {
        return update(new UpdateWrapper<TaskEntity>().lambda()
                .set(TaskEntity::getStatus, TaskStatus.RUNNING)
                .set(TaskEntity::getExecuteNodeKey, executeNodeKey)
                .set(TaskEntity::getStartTime, now)
                .set(TaskEntity::getUpdatedAt, now)
                .eq(TaskEntity::getId, taskId)
                .eq(TaskEntity::getStatus, TaskStatus.CLAIMED));
    }

    @Override
    public boolean updateSuccess(Long taskId, String artifactId, LocalDateTime now) {
        return update(new UpdateWrapper<TaskEntity>().lambda()
                .set(TaskEntity::getStatus, TaskStatus.SUCCEEDED)
                .set(TaskEntity::getResultArtifactId, artifactId)
                .set(TaskEntity::getEndTime, now)
                .set(TaskEntity::getUpdatedAt, now)
                .eq(TaskEntity::getId, taskId)
                .eq(TaskEntity::getStatus, TaskStatus.RUNNING));
    }

    @Override
    public boolean updateFailure(Long taskId,
                                 TaskStatus status,
                                 String errorCode,
                                 String errorMessage,
                                 LocalDateTime now) {
        UpdateWrapper<TaskEntity> wrapper = new UpdateWrapper<>();
        wrapper.lambda()
                .set(TaskEntity::getStatus, status)
                .set(TaskEntity::getErrorCode, errorCode)
                .set(TaskEntity::getErrorMessage, errorMessage)
                .set(TaskEntity::getEndTime, now)
                .set(TaskEntity::getUpdatedAt, now)
                .eq(TaskEntity::getId, taskId)
                .eq(TaskEntity::getStatus, TaskStatus.RUNNING);
        if (TaskStatus.RETRYABLE.equals(status)) {
            wrapper.setSql("retry_count = retry_count + 1");
        }
        return update(wrapper);
    }

    @Override
    public boolean updateFailureFromStatuses(Long taskId,
                                             Collection<TaskStatus> statuses,
                                             TaskStatus status,
                                             String errorCode,
                                             String errorMessage,
                                             LocalDateTime now) {
        if (statuses == null || statuses.isEmpty()) {
            return false;
        }
        UpdateWrapper<TaskEntity> wrapper = new UpdateWrapper<>();
        wrapper.lambda()
                .set(TaskEntity::getStatus, status)
                .set(TaskEntity::getErrorCode, errorCode)
                .set(TaskEntity::getErrorMessage, errorMessage)
                .set(TaskEntity::getEndTime, now)
                .set(TaskEntity::getUpdatedAt, now)
                .eq(TaskEntity::getId, taskId)
                .in(TaskEntity::getStatus, statuses);
        if (TaskStatus.RETRYABLE.equals(status)) {
            wrapper.setSql("retry_count = retry_count + 1");
        }
        return update(wrapper);
    }

    @Override
    public boolean releaseClaimed(Long taskId, LocalDateTime now) {
        return update(new UpdateWrapper<TaskEntity>().lambda()
                .set(TaskEntity::getStatus, TaskStatus.PENDING)
                .set(TaskEntity::getExecuteNodeKey, null)
                .set(TaskEntity::getUpdatedAt, now)
                .eq(TaskEntity::getId, taskId)
                .eq(TaskEntity::getStatus, TaskStatus.CLAIMED));
    }

    @Override
    public boolean resetForRetry(Long taskId, String traceId, LocalDateTime now) {
        return update(new UpdateWrapper<TaskEntity>().lambda()
                .set(TaskEntity::getStatus, TaskStatus.PENDING)
                .set(TaskEntity::getTraceId, traceId)
                .set(TaskEntity::getStartTime, null)
                .set(TaskEntity::getEndTime, null)
                .set(TaskEntity::getExecuteNodeKey, null)
                .set(TaskEntity::getErrorCode, null)
                .set(TaskEntity::getErrorMessage, null)
                .set(TaskEntity::getUpdatedAt, now)
                .eq(TaskEntity::getId, taskId)
                .in(TaskEntity::getStatus, TaskStatus.FAILED, TaskStatus.RETRYABLE, TaskStatus.CANCELLED));
    }

    @Override
    public boolean cancel(Long taskId, LocalDateTime now) {
        return update(new UpdateWrapper<TaskEntity>().lambda()
                .set(TaskEntity::getStatus, TaskStatus.CANCELLED)
                .set(TaskEntity::getErrorCode, "CANCELLED")
                .set(TaskEntity::getErrorMessage, "Cancelled by API request")
                .set(TaskEntity::getEndTime, now)
                .set(TaskEntity::getUpdatedAt, now)
                .eq(TaskEntity::getId, taskId)
                .in(TaskEntity::getStatus, TaskStatus.PENDING, TaskStatus.CLAIMED));
    }

    @Override
    public List<TaskEntity> listByExecuteNodeAndStatuses(String executeNodeKey,
                                                         Collection<TaskStatus> statuses,
                                                         int limit) {
        if (limit <= 0 || statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return list(new QueryWrapper<TaskEntity>().lambda()
                .eq(TaskEntity::getExecuteNodeKey, executeNodeKey)
                .in(TaskEntity::getStatus, statuses)
                .orderByAsc(TaskEntity::getUpdatedAt)
                .last("LIMIT " + limit));
    }

    @Override
    public List<TaskEntity> listByStatusesExcludingExecuteNodes(Collection<TaskStatus> statuses,
                                                               Set<String> executeNodeKeys,
                                                               int limit) {
        if (limit <= 0 || statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        QueryWrapper<TaskEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .in(TaskEntity::getStatus, statuses)
                .isNotNull(TaskEntity::getExecuteNodeKey)
                .orderByAsc(TaskEntity::getUpdatedAt)
                .last("LIMIT " + limit);
        if (executeNodeKeys != null && !executeNodeKeys.isEmpty()) {
            wrapper.lambda().notIn(TaskEntity::getExecuteNodeKey, executeNodeKeys);
        }
        return list(wrapper);
    }
}

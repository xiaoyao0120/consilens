package com.consilens.server.infrastructure.db.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.infrastructure.db.entity.TaskEntity;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface TaskService extends IService<TaskEntity> {

    TaskEntity getByTaskKey(String taskKey);

    TaskEntity getBySerialNo(String serialNo);

    TaskEntity lockBySerialNo(String serialNo);

    boolean updateStatus(Long taskId, TaskStatus status, LocalDateTime now);

    boolean updateClaimed(Long taskId, String executeNodeKey, LocalDateTime now);

    boolean updateRunning(Long taskId, String executeNodeKey, LocalDateTime now);

    boolean renewRunning(Long taskId, LocalDateTime now);

    boolean updateSuccess(Long taskId, String artifactId, LocalDateTime now);

    boolean updateFailure(Long taskId, TaskStatus status, String errorCode, String errorMessage, LocalDateTime now);

    boolean updateFailureFromStatuses(Long taskId,
                                      Collection<TaskStatus> statuses,
                                      TaskStatus status,
                                      String errorCode,
                                      String errorMessage,
                                      LocalDateTime now);

    boolean releaseClaimed(Long taskId, LocalDateTime now);

    boolean resetForRetry(Long taskId, String traceId, LocalDateTime now);

    boolean cancel(Long taskId, LocalDateTime now);

    boolean requestCancellation(Long taskId, LocalDateTime now);

    boolean confirmCancellation(Long taskId, LocalDateTime now);

    List<TaskEntity> listByExecuteNodeAndStatuses(String executeNodeKey, Collection<TaskStatus> statuses, int limit);

    List<TaskEntity> listByStatusesExcludingExecuteNodes(Collection<TaskStatus> statuses,
                                                        Set<String> executeNodeKeys,
                                                        int limit);

    List<TaskEntity> listStaleRunning(LocalDateTime before, int limit);
}

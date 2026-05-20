package com.consilens.server.domain.repository;

import com.consilens.server.domain.model.TaskRecord;
import com.consilens.server.domain.enumtype.TaskStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface TaskRepository {

    Optional<TaskRecord> findById(Long id);

    Optional<TaskRecord> findByTaskKey(String taskKey);

    Optional<TaskRecord> findBySerialNo(String serialNo);

    Optional<TaskRecord> lockBySerialNo(String serialNo);

    TaskRecord save(TaskRecord taskRecord);

    boolean updateClaimed(Long taskId, String executeNodeKey, Instant now);

    boolean updateRunning(Long taskId, String executeNodeKey, Instant now);

    boolean updateSuccess(Long taskId, String artifactId, Instant now);

    boolean updateFailure(Long taskId, String errorCode, String errorMessage, Instant now);

    boolean updateRetryable(Long taskId, String errorCode, String errorMessage, Instant now);

    boolean updateFailureFromStatuses(Long taskId,
                                      Collection<TaskStatus> statuses,
                                      String errorCode,
                                      String errorMessage,
                                      Instant now);

    boolean updateRetryableFromStatuses(Long taskId,
                                        Collection<TaskStatus> statuses,
                                        String errorCode,
                                        String errorMessage,
                                        Instant now);

    boolean releaseClaimed(Long taskId, Instant now);

    boolean resetForRetry(Long taskId, String traceId, Instant now);

    boolean cancel(Long taskId, String traceId, Instant now);

    List<TaskRecord> listByExecuteNodeAndStatuses(String executeNodeKey, Collection<TaskStatus> statuses, int limit);

    List<TaskRecord> listByStatusesExcludingExecuteNodes(Collection<TaskStatus> statuses,
                                                         Set<String> executeNodeKeys,
                                                         int limit);
}

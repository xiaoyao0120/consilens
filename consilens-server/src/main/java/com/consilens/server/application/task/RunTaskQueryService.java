package com.consilens.server.application.task;

import com.consilens.server.api.dto.TaskQueryResponse;

public interface RunTaskQueryService {

    TaskQueryResponse getTask(String taskId, String traceId);
}

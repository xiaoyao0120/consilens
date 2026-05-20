package com.consilens.server.application.task;

public interface RunTaskCancelService {

    void cancel(String taskId, String traceId);
}

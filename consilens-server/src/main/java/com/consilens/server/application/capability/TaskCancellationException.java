package com.consilens.server.application.capability;

public class TaskCancellationException extends RuntimeException {

    public TaskCancellationException(String taskKey) {
        super("Task cancellation requested: " + taskKey);
    }
}

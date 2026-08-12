package com.consilens.server.application.task;

import com.consilens.server.domain.model.TaskInstanceRecord;

import java.util.Optional;

public interface RunSerialNoGuardService {

    Optional<TaskInstanceRecord> findExisting(String serialNo);
}

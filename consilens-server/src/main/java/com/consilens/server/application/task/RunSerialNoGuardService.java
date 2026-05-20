package com.consilens.server.application.task;

import com.consilens.server.domain.model.TaskRecord;

import java.util.Optional;

public interface RunSerialNoGuardService {

    Optional<TaskRecord> findExisting(String serialNo);
}

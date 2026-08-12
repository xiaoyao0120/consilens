package com.consilens.server.application.taskdefinition;

import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.api.dto.TaskDefinitionCreateRequest;
import com.consilens.server.api.dto.TaskDefinitionDetailDto;
import com.consilens.server.api.dto.TaskDefinitionDto;
import com.consilens.server.api.dto.TaskDefinitionRunRequest;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.application.task.RunTaskSubmissionService;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.TaskDefinitionPage;
import com.consilens.server.domain.model.TaskDefinitionRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskDefinitionRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TaskDefinitionServiceImpl implements TaskDefinitionService {

    private final TaskDefinitionRepository definitionRepository;
    private final TaskRepository taskRepository;
    private final RunTaskSubmissionService submissionService;
    private final ServerCompareConfigService configService;
    private final ObjectMapper objectMapper;

    @Autowired
    public TaskDefinitionServiceImpl(TaskDefinitionRepository definitionRepository,
                                        TaskRepository taskRepository,
                                        RunTaskSubmissionService submissionService,
                                        ServerCompareConfigService configService,
                                        ObjectMapper objectMapper) {
        this.definitionRepository = definitionRepository;
        this.taskRepository = taskRepository;
        this.submissionService = submissionService;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskDefinitionDto create(TaskDefinitionCreateRequest request) {
        definitionRepository.findByName(request.getName()).ifPresent(record -> {
            throw new IllegalArgumentException("task definition name already exists: " + request.getName());
        });
        validateConfig(request.getConfig());
        TaskDefinitionRecord saved = definitionRepository.save(TaskDefinitionRecord.builder()
                .definitionKey("taskdef_" + UUID.randomUUID())
                .name(request.getName())
                .description(request.getDescription())
                .taskType("RUN")
                .config(toConfigJson(request.getConfig()))
                .enabled(true)
                .build());
        return toDto(saved);
    }

    @Override
    public PageResponse<TaskDefinitionDto> list(int page, int pageSize, String keyword, Boolean enabled) {
        TaskDefinitionPage result = definitionRepository.listPage(page, pageSize, keyword, enabled);
        return PageResponse.<TaskDefinitionDto>builder()
                .total(result.getTotal())
                .page(page)
                .pageSize(pageSize)
                .items(result.getItems().stream().map(this::toDto).collect(Collectors.toList()))
                .build();
    }

    @Override
    public TaskDefinitionDetailDto get(Long id) {
        TaskDefinitionRecord record = require(id);
        List<TaskDefinitionDetailDto.RecentInstanceDto> recent = taskRepository
                .listByDefinitionId(id, 5)
                .stream()
                .map(this::toRecentInstance)
                .collect(Collectors.toList());
        return TaskDefinitionDetailDto.builder()
                .id(String.valueOf(record.getId()))
                .name(record.getName())
                .description(record.getDescription())
                .taskType(record.getTaskType())
                .enabled(record.getEnabled())
                .lastRunAt(record.getLastRunAt())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .config(fromConfigJson(record.getConfig()))
                .recentInstances(recent)
                .build();
    }

    @Override
    public TaskDefinitionDto update(Long id, TaskDefinitionCreateRequest request) {
        TaskDefinitionRecord record = require(id);
        definitionRepository.findByName(request.getName())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("task definition name already exists: " + request.getName());
                });
        validateConfig(request.getConfig());
        record.setName(request.getName());
        record.setDescription(request.getDescription());
        record.setConfig(toConfigJson(request.getConfig()));
        return toDto(definitionRepository.save(record));
    }

    @Override
    public void delete(Long id) {
        definitionRepository.deleteById(id);
    }

    @Override
    public TaskAcceptedResponse run(Long id, TaskDefinitionRunRequest request, String traceId) {
        TaskDefinitionRecord record = require(id);
        if (!Boolean.TRUE.equals(record.getEnabled())) {
            throw new IllegalArgumentException("task definition is disabled: " + record.getName());
        }
        RunRequest runRequest = new RunRequest();
        runRequest.setSerialNo(request != null && request.getSerialNo() != null && !request.getSerialNo().isBlank()
                ? request.getSerialNo()
                : "def-" + record.getName().substring(0, Math.min(record.getName().length(), 40))
                        + "-" + UUID.randomUUID().toString().substring(0, 8));
        runRequest.setConfigContent(fromConfigJson(record.getConfig()));
        runRequest.setDefinitionId(record.getId());
        if (request != null) {
            runRequest.setOptions(request.getOptions());
        }
        TaskAcceptedResponse accepted = submissionService.submit(runRequest, traceId);
        definitionRepository.updateLastRunAt(id, java.time.Instant.now());
        return accepted;
    }

    @Override
    public TaskDefinitionDto toggle(Long id, boolean enabled) {
        TaskDefinitionRecord record = require(id);
        record.setEnabled(enabled);
        return toDto(definitionRepository.save(record));
    }

    private void validateConfig(Map<String, Object> config) {
        rejectInlinePasswords(config, "source");
        rejectInlinePasswords(config, "target");
        validateConfigSchema(config);
    }

    @SuppressWarnings("unchecked")
    private void rejectInlinePasswords(Map<String, Object> config, String side) {
        Object raw = config.get(side);
        if (!(raw instanceof Map)) {
            return;
        }
        Object connection = ((Map<String, Object>) raw).get("connection");
        if (connection instanceof Map && ((Map<?, ?>) connection).containsKey("password")) {
            throw new IllegalArgumentException(
                    "task definition config must reference data sources via datasourceId; "
                            + "inline connection passwords are not allowed");
        }
    }

    private void validateConfigSchema(Map<String, Object> config) {
        try {
            ServerCompareConfig parsed = objectMapper.convertValue(config, ServerCompareConfig.class);
            configService.validate(parsed);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid task definition config: " + exception.getMessage());
        }
    }

    private TaskDefinitionRecord require(Long id) {
        return definitionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("task definition not found: " + id));
    }

    private TaskDefinitionDto toDto(TaskDefinitionRecord record) {
        return TaskDefinitionDto.builder()
                .id(String.valueOf(record.getId()))
                .name(record.getName())
                .description(record.getDescription())
                .taskType(record.getTaskType())
                .enabled(record.getEnabled())
                .lastRunAt(record.getLastRunAt())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private TaskDefinitionDetailDto.RecentInstanceDto toRecentInstance(TaskInstanceRecord record) {
        return TaskDefinitionDetailDto.RecentInstanceDto.builder()
                .instanceId(record.getInstanceKey())
                .serialNo(record.getSerialNo())
                .status(record.getStatus() != null ? record.getStatus().name() : null)
                .submitTime(record.getSubmitTime())
                .endTime(record.getEndTime())
                .build();
    }

    private String toConfigJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid task definition config");
        }
    }

    private Map<String, Object> fromConfigJson(String config) {
        try {
            return objectMapper.readValue(config, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            throw new IllegalStateException("task definition config is corrupted");
        }
    }
}

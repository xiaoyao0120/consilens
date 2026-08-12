package com.consilens.server.application.task.impl;

import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.application.task.RunTaskSubmissionService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.exception.ConflictException;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.server.support.crypto.CryptoSupport;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.support.hash.Sha256Support;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

@Service
public class RunTaskSubmissionServiceImpl implements RunTaskSubmissionService {

    private final TaskRepository taskRepository;
    private final RunTaskCommandEnqueueService runTaskCommandEnqueueService;
    private final ConsilensServerProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;
    private final CryptoSupport cryptoSupport;
    private final TransactionTemplate transactionTemplate;

    public RunTaskSubmissionServiceImpl(TaskRepository taskRepository,
                                        RunTaskCommandEnqueueService runTaskCommandEnqueueService,
                                        ConsilensServerProperties properties,
                                        ObjectMapper objectMapper,
                                        TransactionTemplate transactionTemplate,
                                        CryptoSupport cryptoSupport) {
        this.taskRepository = taskRepository;
        this.runTaskCommandEnqueueService = runTaskCommandEnqueueService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.yamlMapper = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        this.cryptoSupport = cryptoSupport;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public TaskAcceptedResponse submit(RunRequest request, String traceId) {
        // 持久化前遮蔽连接凭据：hash 基于固定掩码（保证幂等去重），存储用加密版
        String requestHash = Sha256Support.hex(toJson(maskPasswords(request)));
        String requestPayload = toJson(protectPasswords(request));
        try {
            return transactionTemplate.execute(status -> submitInTransaction(request,
                    traceId,
                    requestPayload,
                    requestHash));
        } catch (DataIntegrityViolationException exception) {
            return recoverConcurrentSubmit(request, requestHash, exception);
        }
    }

    private TaskAcceptedResponse submitInTransaction(RunRequest request,
                                                     String traceId,
                                                     String requestPayload,
                                                     String requestHash) {
        Optional<TaskInstanceRecord> existing = taskRepository.lockBySerialNo(request.getSerialNo());
        if (existing.isPresent()) {
            return acceptExisting(requestHash, existing.get());
        }
        Instant now = Instant.now();
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .instanceKey("inst_" + UUID.randomUUID())
                .serialNo(request.getSerialNo())
                .definitionId(request.getDefinitionId())
                .traceId(traceId)
                .requestPayload(requestPayload)
                .requestHash(requestHash)
                .status(TaskStatus.PENDING)
                .priority(5)
                .scheduleTime(now)
                .submitTime(now)
                .retryCount(0)
                .maxRetryCount(properties.getScheduler().getMaxRetryCount())
                .createdAt(now)
                .updatedAt(now)
                .build();
        TaskInstanceRecord savedTask = taskRepository.save(task);

        runTaskCommandEnqueueService.enqueue(savedTask, now);
        return accepted(savedTask);
    }

    private TaskAcceptedResponse recoverConcurrentSubmit(RunRequest request,
                                                         String requestHash,
                                                         DataIntegrityViolationException exception) {
        return taskRepository.findBySerialNo(request.getSerialNo())
                .map(task -> acceptExisting(requestHash, task))
                .orElseThrow(() -> exception);
    }

    private TaskAcceptedResponse acceptExisting(String requestHash, TaskInstanceRecord task) {
        if (!requestHash.equals(task.getRequestHash())) {
            throw new ConflictException("serialNo already exists with different request payload",
                    "SERIAL_NO_CONFLICT");
        }
        return accepted(task);
    }

    private TaskAcceptedResponse accepted(TaskInstanceRecord task) {
        return TaskAcceptedResponse.builder()
                .taskId(task.getInstanceKey())
                .taskType("RUN")
                .status(task.getStatus().name())
                .build();
    }

    /**
     * 将 configContent（ServerCompareConfig 形状）中 source/target.connection.password
     * 替换为掩码，防止凭据随运行记录落库；序列化结果同时作为去重 hash 输入。
     */
    private RunRequest maskPasswords(RunRequest request) {
        return transformPasswords(request, "***");
    }

    private RunRequest protectPasswords(RunRequest request) {
        return transformPasswords(request, null);
    }

    /**
     * 统一解析 configContent（Map 或 JSON/YAML 字符串）并替换 connection.password：
     * mode="***"（固定掩码，供去重 hash）或 mode=null（CryptoSupport.protect 加密，供落库）。
     */
    private RunRequest transformPasswords(RunRequest request, String fixedMask) {
        Object content = request.getConfigContent();
        if (content == null) {
            return request;
        }
        RunRequest copy = new RunRequest();
        copy.setSerialNo(request.getSerialNo());
        copy.setConfigArtifactId(request.getConfigArtifactId());
        copy.setDefinitionId(request.getDefinitionId());
        copy.setOptions(request.getOptions());
        if (content instanceof Map) {
            copy.setConfigContent(transformConnectionPasswords((Map<?, ?>) content, fixedMask));
        } else if (content instanceof String) {
            try {
                Map<?, ?> parsed = yamlMapper.readValue((String) content, Map.class);
                copy.setConfigContent(transformConnectionPasswords(parsed, fixedMask));
            } catch (Exception exception) {
                throw new InvalidInputException("configContent must be a valid JSON/YAML object");
            }
        } else {
            copy.setConfigContent(content);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Object transformConnectionPasswords(Map<?, ?> config, String fixedMask) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : config.entrySet()) {
            Object value = entry.getValue();
            if ("source".equals(entry.getKey()) || "target".equals(entry.getKey())) {
                if (value instanceof Map) {
                    Map<String, Object> endpoint = new java.util.LinkedHashMap<>((Map<String, Object>) value);
                    Object connection = endpoint.get("connection");
                    if (connection instanceof Map) {
                        Map<String, Object> connectionMap =
                                new java.util.LinkedHashMap<>((Map<String, Object>) connection);
                        if (connectionMap.containsKey("password")) {
                            Object rawPassword = connectionMap.get("password");
                            if (rawPassword != null) {
                                connectionMap.put("password",
                                        fixedMask != null
                                                ? fixedMask
                                                : cryptoSupport.protect(String.valueOf(rawPassword)));
                            }
                        }
                        endpoint.put("connection", connectionMap);
                    }
                    result.put((String) entry.getKey(), endpoint);
                    continue;
                }
            }
            result.put((String) entry.getKey(), value);
        }
        return result;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize run request", exception);
        }
    }
}

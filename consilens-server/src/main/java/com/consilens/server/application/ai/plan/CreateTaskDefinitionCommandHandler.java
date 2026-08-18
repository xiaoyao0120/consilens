package com.consilens.server.application.ai.plan;

import com.consilens.agent.api.store.AgentResourceRef;
import com.consilens.agent.api.store.AgentResourceType;
import com.consilens.server.api.dto.TaskDefinitionCreateRequest;
import com.consilens.server.api.dto.TaskDefinitionDto;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.application.taskdefinition.TaskDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes one CREATE_TASK_DEFINITION plan action: injects the actual
 * datasource ids into the frozen config template, re-validates with the
 * production validator and writes through TaskDefinitionService.
 */
public class CreateTaskDefinitionCommandHandler {

    private final TaskDefinitionService taskDefinitionService;
    private final ServerCompareConfigService configService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CreateTaskDefinitionCommandHandler(TaskDefinitionService taskDefinitionService,
                                              ServerCompareConfigService configService) {
        this.taskDefinitionService = taskDefinitionService;
        this.configService = configService;
    }

    @SuppressWarnings("unchecked")
    public AgentResourceRef create(Map<String, Object> safeArgs,
                                   String sourceDatasourceId,
                                   String targetDatasourceId,
                                   String sourceType,
                                   String targetType) {
        String name = (String) safeArgs.get("name");
        String description = safeArgs.get("description") == null ? null : (String) safeArgs.get("description");
        List<String> keys = new ArrayList<>((List<String>) safeArgs.getOrDefault("keys", List.of()));
        List<String> ignoreColumns = new ArrayList<>(
                (List<String>) safeArgs.getOrDefault("ignoreColumns", List.of()));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("version", "1.0");
        config.put("goal", "核对 " + name);
        config.put("source", endpoint(sourceType, sourceDatasourceId,
                (String) safeArgs.getOrDefault("sourceDatabase", ""),
                (String) safeArgs.getOrDefault("sourceTable", "")));
        config.put("target", endpoint(targetType, targetDatasourceId,
                (String) safeArgs.getOrDefault("targetDatabase", ""),
                (String) safeArgs.getOrDefault("targetTable", "")));
        config.put("keys", keys);
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("fields", new ArrayList<>());
        comparison.put("ignoreColumns", ignoreColumns);
        comparison.put("fieldMappings", safeArgs.getOrDefault("fieldMappings", new ArrayList<>()));
        comparison.put("keyMappings", safeArgs.getOrDefault("keyMappings", new ArrayList<>()));
        config.put("comparison", comparison);
        config.put("hints", safeArgs.getOrDefault("strategyHints", Map.of()));
        config.put("executionOptions", Map.of());
        config.put("result", Map.of());

        // Production validator runs against the injected ids before the write.
        ServerCompareConfig parsed = objectMapper.convertValue(config, ServerCompareConfig.class);
        configService.validate(parsed);

        TaskDefinitionCreateRequest request = new TaskDefinitionCreateRequest();
        request.setName(name);
        request.setDescription(description);
        request.setConfig(config);
        TaskDefinitionDto dto = taskDefinitionService.create(request);
        return AgentResourceRef.builder()
                .resourceType(AgentResourceType.TASK_DEFINITION)
                .resourceId(dto.getId())
                .name(dto.getName())
                .build();
    }

    private static Map<String, Object> endpoint(String type, String datasourceId,
                                                String database, String table) {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("type", type);
        endpoint.put("datasourceId", datasourceId);
        endpoint.put("database", database);
        endpoint.put("table", table);
        endpoint.put("connection", Map.of());
        endpoint.put("readOptions", Map.of());
        return endpoint;
    }
}

package com.consilens.server.application.capability.config;

import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.repository.DataSourceRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Config-level validation before a task definition write: both endpoints must
 * reference existing datasources via datasourceId (no inline connections) and
 * the declared endpoint type, when present, must match the datasource type.
 */
@Component
public class TaskDefinitionConfigValidator {

    private final DataSourceRepository dataSourceRepository;

    public TaskDefinitionConfigValidator(DataSourceRepository dataSourceRepository) {
        this.dataSourceRepository = dataSourceRepository;
    }

    @SuppressWarnings("unchecked")
    public void validateDatasourceRefs(Map<String, Object> config) {
        validateSide(config.get("source"), "source");
        validateSide(config.get("target"), "target");
    }

    @SuppressWarnings("unchecked")
    private void validateSide(Object raw, String side) {
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("config." + side + " endpoint is required");
        }
        Map<String, Object> endpoint = (Map<String, Object>) raw;
        Object id = endpoint.get("datasourceId");
        if (id == null || String.valueOf(id).isBlank()) {
            throw new IllegalArgumentException("config." + side + ".datasourceId is required");
        }
        Long datasourceId;
        try {
            datasourceId = Long.valueOf(String.valueOf(id));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("config." + side + ".datasourceId is not a valid id");
        }
        DataSourceRecord datasource = dataSourceRepository.findById(datasourceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "config." + side + " references missing datasource: " + datasourceId));
        Object type = endpoint.get("type");
        if (type != null && !String.valueOf(type).isBlank()
                && !datasource.getType().equals(String.valueOf(type))) {
            throw new IllegalArgumentException(
                    "config." + side + ".type does not match datasource type");
        }
    }
}

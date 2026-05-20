package com.consilens.server.application.capability.config;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareStrategyPreference;
import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.PlanRequest;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.ValidateRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.domain.exception.InvalidInputException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ServerCompareConfigService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public ServerCompareConfigService(ArtifactService artifactService, ObjectMapper objectMapper) {
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    public ServerCompareConfig fromPlanRequest(PlanRequest request) {
        requireEndpointResource("source", request.getSource());
        requireEndpointResource("target", request.getTarget());
        Map<String, Object> hints = copyMap(request.getHints());
        ServerCompareConfig config = ServerCompareConfig.builder()
                .goal(request.getGoal())
                .source(endpoint("source", request.getSource(), hints))
                .target(endpoint("target", request.getTarget(), hints))
                .keys(normalizedStrings(request.getKeys()))
                .comparison(ComparisonConfig.builder()
                        .ignoreColumns(listFrom(hints.get("ignoreColumns")))
                        .fields(listFrom(hints.get("compareColumns")))
                        .build())
                .hints(hints)
                .executionOptions(mapFrom(hints.get("executionOptions")))
                .build();
        validate(config);
        return config;
    }

    public ServerCompareConfig fromValidateRequest(ValidateRequest request) {
        boolean hasArtifact = request.getConfigArtifactId() != null && !request.getConfigArtifactId().isBlank();
        boolean hasContent = request.getConfigContent() != null && !isBlankString(request.getConfigContent());
        if (hasArtifact == hasContent) {
            throw new InvalidInputException("configArtifactId and configContent must contain exactly one value");
        }
        if (hasArtifact) {
            return fromArtifact(request.getConfigArtifactId());
        }
        return fromContent(request.getConfigContent());
    }

    public ServerCompareConfig fromArtifact(String artifactId) {
        ArtifactContentDto content = artifactService.getArtifactContent(artifactId);
        if (!"CONFIG".equals(content.getArtifactType())) {
            throw new InvalidInputException("Artifact is not a CONFIG artifact: " + artifactId);
        }
        return parse(content.getContent());
    }

    public ServerCompareConfig fromContent(Object content) {
        if (content instanceof ServerCompareConfig) {
            ServerCompareConfig config = (ServerCompareConfig) content;
            validate(config);
            return config;
        }
        if (content instanceof String) {
            return parse((String) content);
        }
        ServerCompareConfig config = objectMapper.convertValue(content, ServerCompareConfig.class);
        validate(config);
        return config;
    }

    public void validate(ServerCompareConfig config) {
        if (config == null) {
            throw new InvalidInputException("config cannot be null");
        }
        if (config.getSource() == null || config.getTarget() == null) {
            throw new InvalidInputException("config.source and config.target are required");
        }
        requireEndpointResource("source", config.getSource());
        requireEndpointResource("target", config.getTarget());
        if (config.getKeys() == null || normalizedStrings(config.getKeys()).isEmpty()) {
            throw new InvalidInputException("config.keys cannot be empty");
        }
    }

    public CompareRequest toCompareRequest(ServerCompareConfig config, RunRequest.Options options) {
        validate(config);
        if (config.getSource().getConnection() == null || config.getSource().getConnection().isEmpty()) {
            throw new InvalidInputException("config.source.connection is required for non-dry-run execution");
        }
        if (config.getTarget().getConnection() == null || config.getTarget().getConnection().isEmpty()) {
            throw new InvalidInputException("config.target.connection is required for non-dry-run execution");
        }
        KeySpec keys = KeySpec.builder().fields(normalizedStrings(config.getKeys())).build();
        ComparisonSpec comparisons = ComparisonSpec.builder()
                .fields(emptyToNull(config.getComparison() != null ? config.getComparison().getFields() : null))
                .exclude(emptyToNull(config.getComparison() != null ? config.getComparison().getIgnoreColumns() : null))
                .build();
        return CompareRequest.builder()
                .source(connectorConfig("source", config.getSource()))
                .target(connectorConfig("target", config.getTarget()))
                .sourceKeySpec(keys)
                .targetKeySpec(keys)
                .sourceComparisons(comparisons)
                .targetComparisons(comparisons)
                .sourceFilter(filter(config.getSource()))
                .targetFilter(filter(config.getTarget()))
                .strategyPreference(strategyPreference(config.getHints()))
                .executionOptions(executionOptions(config, options))
                .build();
    }

    public Map<String, Object> validateContent(ServerCompareConfig config) {
        List<String> messages = new ArrayList<>();
        validate(config);
        messages.add("source resource resolved as " + resourceLabel(config.getSource()));
        messages.add("target resource resolved as " + resourceLabel(config.getTarget()));
        if (config.getSource().getConnection() == null || config.getSource().getConnection().isEmpty()) {
            messages.add("source connection is absent; run must use dryRun=true or provide connection details");
        }
        if (config.getTarget().getConnection() == null || config.getTarget().getConnection().isEmpty()) {
            messages.add("target connection is absent; run must use dryRun=true or provide connection details");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", true);
        result.put("messages", messages);
        result.put("configVersion", config.getVersion());
        return result;
    }

    private ServerCompareConfig parse(String content) {
        if (content == null || content.isBlank()) {
            throw new InvalidInputException("configContent cannot be blank");
        }
        try {
            ServerCompareConfig config = objectMapper.readValue(content, ServerCompareConfig.class);
            validate(config);
            return config;
        } catch (JsonProcessingException exception) {
            throw new InvalidInputException("configContent is not valid server config JSON");
        }
    }

    private EndpointConfig endpoint(String side, PlanRequest.Endpoint endpoint, Map<String, Object> hints) {
        return EndpointConfig.builder()
                .type(trim(endpoint.getType()))
                .table(trim(endpoint.getTable()))
                .query(trim(endpoint.getQuery()))
                .filter(trim(firstString(hints.get(side + "Filter"), hints.get("filter"))))
                .connection(connection(side, hints))
                .readOptions(readOptions(side, hints))
                .build();
    }

    private ConnectorConfig connectorConfig(String side, EndpointConfig endpoint) {
        return ConnectorConfig.builder()
                .type(endpoint.getType())
                .name(side)
                .connection(copyMap(endpoint.getConnection()))
                .resource(resource(endpoint))
                .readOptions(readOptions(endpoint.getReadOptions()))
                .build();
    }

    private ResourceLocator resource(EndpointConfig endpoint) {
        if (endpoint.getQuery() != null && !endpoint.getQuery().isBlank()) {
            return ResourceLocator.builder()
                    .type("sql")
                    .path(endpoint.getQuery().trim())
                    .build();
        }
        return ResourceLocator.builder()
                .type("table")
                .name(endpoint.getTable().trim())
                .build();
    }

    private PredicateSpec filter(EndpointConfig endpoint) {
        if (endpoint.getFilter() == null || endpoint.getFilter().isBlank()) {
            return null;
        }
        return PredicateSpec.builder()
                .type("sql")
                .expression(endpoint.getFilter().trim())
                .build();
    }

    private ReadOptions readOptions(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return ReadOptions.builder()
                .consistency(string(raw.get("consistency")))
                .batchSize(integer(raw.get("batchSize")))
                .fetchSize(integer(raw.get("fetchSize")))
                .options(mapFrom(raw.get("options")))
                .build();
    }

    private CompareExecutionOptions executionOptions(ServerCompareConfig config, RunRequest.Options runOptions) {
        Map<String, Object> merged = copyMap(config.getExecutionOptions());
        if (runOptions != null && runOptions.getTimeoutMs() != null) {
            merged.put("timeoutMs", runOptions.getTimeoutMs());
        }
        return CompareExecutionOptions.builder()
                .bisectionFactor(integer(merged.get("bisectionFactor")))
                .bisectionThreshold(longValue(merged.get("bisectionThreshold")))
                .enableProfiling(booleanValue(merged.get("enableProfiling")))
                .checksumAlgorithm(string(merged.get("checksumAlgorithm")))
                .localCompareMode(string(merged.get("localCompareMode")))
                .validateUniqueKeys(booleanValue(merged.get("validateUniqueKeys")))
                .maxDifferences(longValue(merged.get("maxDifferences")))
                .attributes(merged.isEmpty() ? null : merged)
                .build();
    }

    private CompareStrategyPreference strategyPreference(Map<String, Object> hints) {
        if (hints == null) {
            return null;
        }
        Map<String, Object> raw = mapFrom(hints.get("strategyPreference"));
        if (raw.isEmpty()) {
            return null;
        }
        return CompareStrategyPreference.builder()
                .preferredPlans(listFrom(raw.get("preferredPlans")))
                .allowFallback(booleanValue(raw.get("allowFallback")))
                .options(mapFrom(raw.get("options")))
                .build();
    }

    private Map<String, Object> connection(String side, Map<String, Object> hints) {
        Map<String, Object> direct = mapFrom(hints.get(side + "Connection"));
        if (!direct.isEmpty()) {
            return direct;
        }
        Map<String, Object> prefixed = new LinkedHashMap<>();
        putIfPresent(prefixed, "url", hints.get(side + "Url"));
        putIfPresent(prefixed, "username", firstValue(hints.get(side + "Username"), hints.get(side + "User")));
        putIfPresent(prefixed, "password", hints.get(side + "Password"));
        putIfPresent(prefixed, "driver", firstValue(hints.get(side + "Driver"), hints.get(side + "DriverClassName")));
        return prefixed;
    }

    private Map<String, Object> readOptions(String side, Map<String, Object> hints) {
        return mapFrom(hints.get(side + "ReadOptions"));
    }

    private void requireEndpointResource(String side, PlanRequest.Endpoint endpoint) {
        if (endpoint == null) {
            throw new InvalidInputException(side + " endpoint is required");
        }
        if (isBlank(endpoint.getTable()) && isBlank(endpoint.getQuery())) {
            throw new InvalidInputException(side + ".table or " + side + ".query is required");
        }
    }

    private void requireEndpointResource(String side, EndpointConfig endpoint) {
        if (endpoint == null || isBlank(endpoint.getType())) {
            throw new InvalidInputException(side + ".type is required");
        }
        if (isBlank(endpoint.getTable()) && isBlank(endpoint.getQuery())) {
            throw new InvalidInputException(side + ".table or " + side + ".query is required");
        }
    }

    private String resourceLabel(EndpointConfig endpoint) {
        return endpoint.getQuery() != null && !endpoint.getQuery().isBlank()
                ? "sql"
                : "table:" + endpoint.getTable();
    }

    private List<String> normalizedStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> listFrom(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .collect(Collectors.toList());
        }
        if (value instanceof String && !((String) value).isBlank()) {
            return List.of(((String) value).trim());
        }
        return List.of();
    }

    private Map<String, Object> mapFrom(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(objectMapper.convertValue(value, MAP_TYPE));
    }

    private Map<String, Object> copyMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    private <T> List<T> emptyToNull(List<T> value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null && !isBlankString(value)) {
            map.put(key, value);
        }
    }

    private Object firstValue(Object first, Object second) {
        return first != null ? first : second;
    }

    private String firstString(Object first, Object second) {
        String firstString = string(first);
        return firstString != null ? firstString : string(second);
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isBlankString(Object value) {
        return value instanceof String && ((String) value).isBlank();
    }

    private Integer integer(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String && !((String) value).isBlank()) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException exception) {
                throw new InvalidInputException("numeric option must be an integer: " + value);
            }
        }
        return null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !((String) value).isBlank()) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException exception) {
                throw new InvalidInputException("numeric option must be a long: " + value);
            }
        }
        return null;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String && !((String) value).isBlank()) {
            return Boolean.parseBoolean((String) value);
        }
        return null;
    }
}

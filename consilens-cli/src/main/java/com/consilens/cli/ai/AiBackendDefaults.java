package com.consilens.cli.ai;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Persistent backend defaults loaded from the local AI runtime home.
 */
@Value
@Builder
@JsonDeserialize(builder = AiBackendDefaults.AiBackendDefaultsBuilder.class)
public class AiBackendDefaults {

    String defaultBackend;
    BackendDefaults shared;
    Map<String, BackendDefaults> backends;

    public BackendDefaults defaultsFor(String backend) {
        if (backend == null || backend.isBlank() || backends == null) {
            return null;
        }
        return backends.get(backend.trim().toLowerCase());
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class AiBackendDefaultsBuilder {
    }

    @Value
    @Builder
    @JsonDeserialize(builder = BackendDefaults.BackendDefaultsBuilder.class)
    public static class BackendDefaults {

        String model;
        String baseUrl;
        String apiKey;
        String apiKeyEnv;
        String timeout;
        Double temperature;
        Integer maxTokens;

        @JsonPOJOBuilder(withPrefix = "")
        public static class BackendDefaultsBuilder {
        }
    }
}

package com.consilens.server.application.capability.config;

import com.consilens.sink.api.model.ResultConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerCompareConfig {

    @Builder.Default
    private String version = "1.0";

    private String goal;

    private EndpointConfig source;

    private EndpointConfig target;

    @Builder.Default
    private List<String> keys = new ArrayList<>();

    @Builder.Default
    private ComparisonConfig comparison = new ComparisonConfig();

    @Builder.Default
    private Map<String, Object> hints = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Object> executionOptions = new LinkedHashMap<>();

    @Builder.Default
    private ResultConfig result = new ResultConfig();
}

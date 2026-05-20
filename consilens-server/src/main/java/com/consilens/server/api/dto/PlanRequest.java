package com.consilens.server.api.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class PlanRequest {

    @NotBlank
    private String goal;

    @Valid
    @NotNull
    private Endpoint source;

    @Valid
    @NotNull
    private Endpoint target;

    @NotEmpty
    private List<String> keys;

    private Map<String, Object> hints = new LinkedHashMap<>();

    @Data
    public static class Endpoint {
        @NotBlank
        private String type;
        private String table;
        private String query;
    }
}

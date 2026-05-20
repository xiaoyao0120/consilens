package com.consilens.server.api.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class DiagnoseRequest {

    @NotBlank
    private String runArtifactId;

    private Map<String, Object> options = new LinkedHashMap<>();
}

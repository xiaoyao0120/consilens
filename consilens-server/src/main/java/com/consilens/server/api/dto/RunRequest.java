package com.consilens.server.api.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;

@Data
public class RunRequest {

    @NotBlank
    private String serialNo;

    @NotBlank
    private String configArtifactId;

    @Valid
    private Options options = new Options();

    @Data
    public static class Options {
        private Integer timeoutMs;
        private Boolean dryRun;
    }
}

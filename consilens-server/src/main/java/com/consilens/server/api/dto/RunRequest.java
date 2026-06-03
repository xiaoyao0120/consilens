package com.consilens.server.api.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

@Data
public class RunRequest {

    @NotBlank
    @Size(max = ApiValidationRules.ID_MAX_LENGTH)
    private String serialNo;

    @NotBlank
    @Size(max = ApiValidationRules.ID_MAX_LENGTH)
    private String configArtifactId;

    @Valid
    private Options options = new Options();

    @Data
    public static class Options {
        @Positive
        private Integer timeoutMs;
        private Boolean dryRun;
    }
}

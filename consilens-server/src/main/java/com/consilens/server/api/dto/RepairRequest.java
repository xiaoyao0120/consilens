package com.consilens.server.api.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;

@Data
public class RepairRequest {

    @NotBlank
    private String diagnosisArtifactId;

    @Valid
    private Options options = new Options();

    @Data
    public static class Options {
        private Boolean requireApproval;
    }
}

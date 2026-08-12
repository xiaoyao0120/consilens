package com.consilens.server.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import javax.validation.constraints.AssertTrue;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

@Data
public class RunRequest {

    @NotBlank
    @Size(max = ApiValidationRules.ID_MAX_LENGTH)
    private String serialNo;

    @Size(max = ApiValidationRules.ID_MAX_LENGTH)
    private String configArtifactId;

    /** Owning task definition (null for externally submitted runs). */
    private Long definitionId;

    private Object configContent;

    @Valid
    private Options options = new Options();

    @AssertTrue(message = "configArtifactId and configContent must contain exactly one value")
    @JsonIgnore
    public boolean isConfigReferenceValid() {
        boolean hasArtifact = configArtifactId != null && !configArtifactId.isBlank();
        boolean hasContent = configContent != null
                && !(configContent instanceof String && ((String) configContent).isBlank());
        return hasArtifact ^ hasContent;
    }

    @Data
    public static class Options {
        @Positive
        private Integer timeoutMs;
        private Boolean dryRun;
    }
}

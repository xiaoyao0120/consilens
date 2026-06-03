package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.ApiValidationRules;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.support.trace.TraceIdSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.Pattern;

@RestController
@RequestMapping("/v1/artifacts")
@RequiredArgsConstructor
@Validated
public class ArtifactController {

    private final ArtifactService artifactService;

    @GetMapping("/{artifactId}")
    public ResponseEntity<ApiResponse<ArtifactRefDto>> getArtifact(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String artifactId,
                                                                   HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(artifactService.getArtifact(artifactId), traceId));
    }

    @GetMapping("/{artifactId}/content")
    public ResponseEntity<ApiResponse<ArtifactContentDto>> getArtifactContent(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String artifactId,
                                                                              HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(artifactService.getArtifactContent(artifactId), traceId));
    }
}

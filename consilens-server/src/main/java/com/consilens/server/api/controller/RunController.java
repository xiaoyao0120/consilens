package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.application.task.RunTaskSubmissionService;
import com.consilens.server.support.trace.TraceIdSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class RunController {

    private final RunTaskSubmissionService runTaskSubmissionService;

    @PostMapping("/run")
    public ResponseEntity<ApiResponse<TaskAcceptedResponse>> run(@Valid @RequestBody RunRequest request,
                                                                 HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(runTaskSubmissionService.submit(request, traceId), traceId));
    }
}

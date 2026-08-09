package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.ApiValidationRules;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.application.task.RunTaskCancelService;
import com.consilens.server.application.task.RunTaskQueryService;
import com.consilens.server.application.task.RunTaskRetryService;
import com.consilens.server.application.task.RunTaskSubmissionService;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.support.trace.TraceIdSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.Pattern;

@RestController
@RequestMapping("/v1/tasks")
@RequiredArgsConstructor
@Validated
public class TaskController {

    private final RunTaskQueryService runTaskQueryService;
    private final RunTaskRetryService runTaskRetryService;
    private final RunTaskCancelService runTaskCancelService;
    private final RunTaskSubmissionService runTaskSubmissionService;

    @PostMapping("/execute")
    public ResponseEntity<ApiResponse<TaskAcceptedResponse>> submit(
            @Valid @RequestBody RunRequest request,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(runTaskSubmissionService.submit(request, traceId), traceId));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<TaskQueryResponse>> getTask(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String taskId,
                                                                  HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(runTaskQueryService.getTask(taskId, traceId), traceId));
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<ApiResponse<Void>> retry(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String taskId,
                                                   HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        runTaskRetryService.retry(taskId, traceId);
        return ResponseEntity.ok(ApiResponse.success(null, traceId));
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String taskId,
                                                    HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        TaskStatus status = runTaskCancelService.cancel(taskId, traceId);
        if (status == TaskStatus.CANCEL_REQUESTED) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(null, traceId));
        }
        return ResponseEntity.ok(ApiResponse.success(null, traceId));
    }
}

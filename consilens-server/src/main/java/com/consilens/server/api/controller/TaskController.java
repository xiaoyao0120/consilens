package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.application.task.RunTaskCancelService;
import com.consilens.server.application.task.RunTaskQueryService;
import com.consilens.server.application.task.RunTaskRetryService;
import com.consilens.server.support.trace.TraceIdSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final RunTaskQueryService runTaskQueryService;
    private final RunTaskRetryService runTaskRetryService;
    private final RunTaskCancelService runTaskCancelService;

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<TaskQueryResponse>> getTask(@PathVariable String taskId,
                                                                  HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(runTaskQueryService.getTask(taskId, traceId), traceId));
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<ApiResponse<Void>> retry(@PathVariable String taskId,
                                                   HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        runTaskRetryService.retry(taskId, traceId);
        return ResponseEntity.ok(ApiResponse.success(null, traceId));
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable String taskId,
                                                    HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        runTaskCancelService.cancel(taskId, traceId);
        return ResponseEntity.ok(ApiResponse.success(null, traceId));
    }
}

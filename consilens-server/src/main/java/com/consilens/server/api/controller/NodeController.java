package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.NodeSummaryDto;
import com.consilens.server.application.topology.ServerNodeQueryService;
import com.consilens.server.support.trace.TraceIdSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/v1/nodes")
@RequiredArgsConstructor
public class NodeController {

    private final ServerNodeQueryService serverNodeQueryService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<NodeSummaryDto>>> listNodes(HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(serverNodeQueryService.listNodes(), traceId));
    }

    @GetMapping("/{nodeKey}")
    public ResponseEntity<ApiResponse<NodeSummaryDto>> getNode(@PathVariable String nodeKey,
                                                               HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(serverNodeQueryService.getNode(nodeKey), traceId));
    }
}

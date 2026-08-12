package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.ApiValidationRules;
import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.api.dto.DataSourceCreateRequest;
import com.consilens.server.api.dto.DataSourceDto;
import com.consilens.server.api.dto.DataSourceTypeDto;
import com.consilens.server.api.dto.MetadataColumnDto;
import com.consilens.server.application.datasource.DataSourceService;
import com.consilens.server.support.trace.TraceIdSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.Pattern;
import java.util.List;

@Validated
@RestController
@RequestMapping("/v1/datasources")
public class DataSourceController {

    /** 库名/表名允许的字符（比 id 宽松，避免误伤含特殊字符的表名）。 */
    private static final String METADATA_NAME_PATTERN = "[A-Za-z0-9_.$: \\-]{1,256}";

    private final DataSourceService dataSourceService;

    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @GetMapping("/types")
    public ResponseEntity<ApiResponse<List<DataSourceTypeDto>>> listTypes(HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(dataSourceService.listTypes(), traceId));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DataSourceDto>>> list(HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(dataSourceService.list(), traceId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DataSourceDto>> create(
            @Valid @RequestBody DataSourceCreateRequest request,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(dataSourceService.create(request), traceId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSourceDto>> get(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(dataSourceService.get(id), traceId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSourceDto>> update(
            @PathVariable Long id,
            @Valid @RequestBody DataSourceCreateRequest request,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(dataSourceService.update(id, request), traceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        dataSourceService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, traceId));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<ApiResponse<ConnectionTestResponse>> test(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(dataSourceService.test(id), traceId));
    }

    @GetMapping("/{id}/databases")
    public ResponseEntity<ApiResponse<List<String>>> listDatabases(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(dataSourceService.getDatabases(id), traceId));
    }

    @GetMapping("/{id}/databases/{database}/tables")
    public ResponseEntity<ApiResponse<List<String>>> listTables(
            @PathVariable Long id,
            @PathVariable @Pattern(regexp = METADATA_NAME_PATTERN,
                    message = "database contains invalid characters") String database,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(dataSourceService.getTables(id, database), traceId));
    }

    @GetMapping("/{id}/databases/{database}/tables/{table}/columns")
    public ResponseEntity<ApiResponse<List<MetadataColumnDto>>> listColumns(
            @PathVariable Long id,
            @PathVariable @Pattern(regexp = METADATA_NAME_PATTERN,
                    message = "database contains invalid characters") String database,
            @PathVariable @Pattern(regexp = METADATA_NAME_PATTERN,
                    message = "table contains invalid characters") String table,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(dataSourceService.getColumns(id, database, table), traceId));
    }
}

package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class SearchTablesOutput {
    List<TableCandidate> candidates;
    boolean truncated;
    /** 连接失败被跳过的数据源名称（不影响其他数据源的结果）。 */
    List<String> skippedDatasources;
}

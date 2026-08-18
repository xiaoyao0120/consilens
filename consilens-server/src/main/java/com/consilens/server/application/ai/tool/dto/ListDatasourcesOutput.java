package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ListDatasourcesOutput {
    List<DatasourceRef> datasources;
    /** 结果超过上限被截断时为 true。 */
    boolean truncated;
}

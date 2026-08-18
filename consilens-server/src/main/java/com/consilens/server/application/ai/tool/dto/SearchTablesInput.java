package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import lombok.Value;

@Value
@Builder
@Jacksonized
public class SearchTablesInput {
    /** 必填：表名/库名关键词（不区分大小写，包含匹配）。 */
    String keyword;
    /** 可选：限定单个数据源（传数据源 id）；缺省搜索全部数据源。 */
    String datasourceId;
}

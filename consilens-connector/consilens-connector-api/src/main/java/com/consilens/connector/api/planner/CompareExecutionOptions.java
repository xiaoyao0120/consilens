package com.consilens.connector.api.planner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompareExecutionOptions {

    private Integer bisectionFactor;

    private Long bisectionThreshold;

    private String checksumAlgorithm;

    private String localCompareMode;

    private Boolean validateUniqueKeys;

    /**
     * 是否允许可空键列。默认 false：键列在元数据中可空时直接拒绝比对，避免 NULL 键行
     * 因 SQL 三值逻辑落在所有分段之外而静默漏报。显式设为 true 时保留可空键列，
     * NULL 键按引擎的 IS NULL/归一化语义参与分段与比较。
     */
    private Boolean allowNullableKeys;

    private Long maxDifferences;

    private Map<String, Object> attributes;
}

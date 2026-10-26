package com.consilens.connector.api.normalization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把“按类型配置”的归一化规则（{@code decimal: {precision: 6}}）编译成
 * {@link NormalizationSpec}，供 CLI 与 server 共用。
 *
 * <p>两侧共用同一实现的原因：归一化契约必须与 SQL 生成、本地比对完全一致，
 * 一旦 CLI 与 server 各自解释配置，同一份定义在两种入口下会得到不同结论。
 */
public final class NormalizationSpecs {

    private NormalizationSpecs() {
    }

    /**
     * @param global 全局规则，key 为类型名，value 为该类型的字段集合
     * @param source 仅源端生效的规则（覆盖 global）
     * @param target 仅目标端生效的规则（覆盖 global）
     * @return 空规则时返回 null，便于 {@code CompareRequest} 区分“未配置”
     */
    public static NormalizationSpec fromTypeRules(Map<String, Map<String, Object>> global,
                                                  Map<String, Map<String, Object>> source,
                                                  Map<String, Map<String, Object>> target) {
        List<NormalizationRule> globalRules = toRules(global);
        List<NormalizationRule> sourceRules = toRules(source);
        List<NormalizationRule> targetRules = toRules(target);
        if (globalRules == null && sourceRules == null && targetRules == null) {
            return null;
        }
        return NormalizationSpec.builder()
                .global(globalRules)
                .source(sourceRules)
                .target(targetRules)
                .build();
    }

    static List<NormalizationRule> toRules(Map<String, Map<String, Object>> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        List<NormalizationRule> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : rules.entrySet()) {
            String canonicalType = NormalizationTypeMapper.toMatchType(entry.getKey());
            Map<String, Object> rule = entry.getValue();
            if (canonicalType == null || rule == null) {
                continue;
            }
            result.addAll(toRules(canonicalType, rule));
        }
        return result.isEmpty() ? null : result;
    }

    static List<NormalizationRule> toRules(String type, Map<String, Object> rule) {
        List<NormalizationRule> result = new ArrayList<>();
        Integer precision = integer(rule.get("precision"));
        Boolean rounding = bool(rule.get("rounding"));
        String format = string(rule.get("format"));
        String timezone = string(rule.get("timezone"));
        String comparisonMode = string(rule.get("comparisonMode"));
        // 会话时区是"连接级"信息，但按类型配置语法只能挂在某个类型的规则上；
        // 只要出现在任一规则里，core 侧就会读取它（见 CompareExecutionSettings.resolveSessionZone）。
        String sessionTimeZone = string(rule.get("sessionTimeZone"));
        if (sessionTimeZone == null) {
            sessionTimeZone = string(rule.get("session_time_zone"));
        }

        if (precision != null || rounding != null) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (precision != null) {
                params.put("precision", precision);
            }
            if (rounding != null) {
                params.put("rounding", rounding);
            }
            result.add(rule(type, "format_number", params));
        }

        if (format != null || timezone != null || comparisonMode != null || sessionTimeZone != null) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (format != null) {
                params.put("format", format);
            }
            if (timezone != null) {
                params.put("timezone", timezone);
            }
            if (comparisonMode != null) {
                params.put("comparisonMode", comparisonMode);
            }
            if (sessionTimeZone != null) {
                params.put("sessionTimeZone", sessionTimeZone);
            }
            result.add(rule(type, "format_datetime", params));
        }

        String encoding = string(rule.get("encoding"));
        Boolean uppercase = bool(rule.get("uppercase"));
        if (encoding != null || uppercase != null) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (encoding != null) {
                params.put("encoding", encoding);
            }
            if (uppercase != null) {
                params.put("uppercase", uppercase);
            }
            result.add(rule(type, "encode", params));
        }

        String trueValue = string(rule.get("trueValue"));
        String falseValue = string(rule.get("falseValue"));
        String nullValue = string(rule.get("nullValue"));
        if ("boolean".equals(type) && (trueValue != null || falseValue != null || nullValue != null)) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (trueValue != null) {
                params.put("trueValue", trueValue);
            }
            if (falseValue != null) {
                params.put("falseValue", falseValue);
            }
            if (nullValue != null) {
                params.put("nullValue", nullValue);
            }
            result.add(rule(type, "map_boolean", params));
        } else if ("string".equals(type) && nullValue != null) {
            result.add(rule(type, "normalize_string", Map.of("nullValue", nullValue)));
        }
        return result;
    }

    private static NormalizationRule rule(String type, String operation, Map<String, Object> params) {
        return NormalizationRule.builder()
                .match(MatchSpec.builder().type(type).build())
                .operation(operation)
                .params(params)
                .build();
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String && !((String) value).isBlank()) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String && !((String) value).isBlank()) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        return null;
    }
}

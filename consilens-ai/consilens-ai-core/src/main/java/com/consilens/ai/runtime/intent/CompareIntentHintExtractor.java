package com.consilens.ai.runtime.intent;

import com.consilens.ai.execution.model.ConfigGenerationRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts deterministic compare hints from natural-language goals so planning, REPL,
 * and legacy runtime entry points all seed the config generator consistently.
 */
public final class CompareIntentHintExtractor {

    private static final List<String> CONNECTOR_TYPES = Arrays.asList(
            "mysql", "postgresql", "postgres", "oracle", "clickhouse", "sqlserver",
            "starrocks", "doris", "tidb", "trino", "presto");
    private static final String CONNECTOR_PATTERN = "mysql|postgresql|postgres|oracle|clickhouse|sqlserver|starrocks|doris|tidb|trino|presto";
    private static final Pattern QUALIFIED_RESOURCE_PATTERN = Pattern.compile("(?i)\\b([a-z_][a-z0-9_]*)\\.([a-z_][a-z0-9_]*)\\b");
    private static final Pattern CONNECTOR_RESOURCE_PATTERN = Pattern.compile(
            "(?i)\\b(" + CONNECTOR_PATTERN + ")\\b\\s*(?:\\.|:|->|to)?\\s*(?:table\\s+)?([a-z_][a-z0-9_]*)\\b");
    private static final Pattern CN_TABLE_PATTERN = Pattern.compile("(?i)([a-z_][a-z0-9_]*)\\s*表");
    private static final Pattern EN_TABLE_PATTERN = Pattern.compile("(?i)\\b(?:table\\s+([a-z_][a-z0-9_]*)|([a-z_][a-z0-9_]*)\\s+table)\\b");
    private static final Pattern KEYS_PATTERN = Pattern.compile("(?i)\\bkeys?\\s*[:=]?\\s*([a-z_][a-z0-9_]*(?:\\s*,\\s*[a-z_][a-z0-9_]*)*)");
    private static final Pattern SOURCE_KEYS_PATTERN = Pattern.compile("(?i)\\bsourcekeys\\s*[:=]?\\s*([a-z_][a-z0-9_]*(?:\\s*,\\s*[a-z_][a-z0-9_]*)*)");
    private static final Pattern TARGET_KEYS_PATTERN = Pattern.compile("(?i)\\btargetkeys\\s*[:=]?\\s*([a-z_][a-z0-9_]*(?:\\s*,\\s*[a-z_][a-z0-9_]*)*)");
    private static final Pattern CN_KEYS_PATTERN = Pattern.compile("(?i)主键(?:字段)?\\s*[:：是为]?\\s*([a-z_][a-z0-9_]*(?:\\s*,\\s*[a-z_][a-z0-9_]*)*)");

    private CompareIntentHintExtractor() {
    }

    public static ConfigGenerationRequest enrich(String sessionId, String goal, List<String> existingHints) {
        ConfigGenerationRequest.ConfigGenerationRequestBuilder builder = ConfigGenerationRequest.builder()
                .sessionId(sessionId)
                .goal(goal);
        Map<String, String> merged = new LinkedHashMap<>();
        mergeHints(merged, existingHints);
        inferHints(goal).forEach(merged::putIfAbsent);
        merged.forEach((key, value) -> builder.hint(key + "=" + value));
        return builder.build();
    }

    public static Map<String, String> inferHints(String text) {
        Map<String, String> hints = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return hints;
        }

        List<String> qualifiedResources = extractQualifiedResources(text);
        if (qualifiedResources.size() >= 2) {
            addResourceHints(hints, "source", qualifiedResources.get(0));
            addResourceHints(hints, "target", qualifiedResources.get(1));
        } else {
            List<String> connectorResources = extractConnectorResources(text);
            if (connectorResources.size() >= 2) {
                addResourceHints(hints, "source", connectorResources.get(0));
                addResourceHints(hints, "target", connectorResources.get(1));
            }
            List<String> connectors = extractConnectors(text);
            String sharedTable = extractSharedTable(text);
            if (!hints.containsKey("sourceType") && connectors.size() >= 2) {
                hints.put("sourceType", connectors.get(0));
                hints.put("targetType", connectors.get(1));
                if (sharedTable != null && !sharedTable.isBlank()) {
                    hints.put("sourceTable", sharedTable);
                    hints.put("targetTable", sharedTable);
                }
            }
        }

        matchFirst(KEYS_PATTERN, text).ifPresent(keys -> hints.put("keys", keys));
        matchFirst(SOURCE_KEYS_PATTERN, text).ifPresent(keys -> hints.put("sourceKeys", keys));
        matchFirst(TARGET_KEYS_PATTERN, text).ifPresent(keys -> hints.put("targetKeys", keys));
        if (!hints.containsKey("keys")) {
            matchFirst(CN_KEYS_PATTERN, text).ifPresent(keys -> hints.put("keys", keys));
        }
        return hints;
    }

    public static boolean looksLikeComparePlan(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        boolean compareVerb = normalized.contains("compare")
                || normalized.contains("diff")
                || normalized.contains("对比")
                || normalized.contains("比对")
                || normalized.contains("比较")
                || normalized.contains("差异")
                || normalized.contains("一致性");
        if (!compareVerb) {
            return false;
        }
        Map<String, String> hints = inferHints(text);
        boolean hasSourceTarget = hints.containsKey("sourceType") && hints.containsKey("targetType");
        boolean hasResource = hints.containsKey("sourceTable")
                || normalized.contains("source:")
                || normalized.contains("target:")
                || normalized.contains("select ")
                || normalized.contains(" sql");
        return hasSourceTarget && hasResource;
    }

    private static void mergeHints(Map<String, String> merged, List<String> hints) {
        if (hints == null) {
            return;
        }
        for (String hint : hints) {
            if (hint == null) {
                continue;
            }
            int index = hint.indexOf('=');
            if (index <= 0 || index == hint.length() - 1) {
                continue;
            }
            merged.put(hint.substring(0, index), hint.substring(index + 1));
        }
    }

    private static void addResourceHints(Map<String, String> hints, String prefix, String qualifiedResource) {
        int index = qualifiedResource.indexOf('.');
        if (index <= 0 || index == qualifiedResource.length() - 1) {
            return;
        }
        hints.put(prefix + "Type", qualifiedResource.substring(0, index));
        hints.put(prefix + "Table", qualifiedResource.substring(index + 1));
    }

    private static List<String> extractQualifiedResources(String text) {
        List<String> resources = new ArrayList<>();
        Matcher matcher = QUALIFIED_RESOURCE_PATTERN.matcher(text);
        while (matcher.find()) {
            String connector = normalizeConnector(matcher.group(1));
            if (connector == null) {
                continue;
            }
            resources.add(connector + "." + matcher.group(2));
        }
        return resources;
    }

    private static List<String> extractConnectors(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> connectors = new LinkedHashSet<>();
        for (String connector : CONNECTOR_TYPES) {
            if (normalized.contains(connector)) {
                connectors.add(normalizeConnector(connector));
            }
        }
        return new ArrayList<>(connectors);
    }

    private static List<String> extractConnectorResources(String text) {
        List<String> resources = new ArrayList<>();
        Matcher matcher = CONNECTOR_RESOURCE_PATTERN.matcher(text);
        while (matcher.find()) {
            String connector = normalizeConnector(matcher.group(1));
            String resource = matcher.group(2);
            if (connector == null || isConnectorNoise(resource)) {
                continue;
            }
            resources.add(connector + "." + resource);
        }
        return resources;
    }

    private static String extractSharedTable(String text) {
        Matcher cnMatcher = CN_TABLE_PATTERN.matcher(text);
        if (cnMatcher.find()) {
            return cnMatcher.group(1);
        }
        Matcher enMatcher = EN_TABLE_PATTERN.matcher(text);
        if (enMatcher.find()) {
            return enMatcher.group(1) != null ? enMatcher.group(1) : enMatcher.group(2);
        }
        return null;
    }

    private static java.util.Optional<String> matchFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(matcher.group(1)).map(String::trim);
    }

    private static String normalizeConnector(String connector) {
        if (connector == null) {
            return null;
        }
        String normalized = connector.toLowerCase(Locale.ROOT);
        if ("postgres".equals(normalized)) {
            return "postgresql";
        }
        return CONNECTOR_TYPES.contains(normalized) ? normalized : null;
    }

    private static boolean isConnectorNoise(String token) {
        if (token == null) {
            return true;
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        return Arrays.asList("and", "or", "with", "between", "from", "into", "to", "中", "表", "data").contains(normalized);
    }
}

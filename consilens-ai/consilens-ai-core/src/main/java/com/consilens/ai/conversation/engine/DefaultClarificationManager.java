package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.QuestionSpec;
import com.consilens.ai.session.model.PendingQuestionState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal clarification manager that appends the answer back into the original request.
 */
public class DefaultClarificationManager implements ClarificationManager {

    private static final Pattern LINE_KEY_VALUE = Pattern.compile("(?im)^\\s*([a-zA-Z][a-zA-Z0-9_-]{1,40})\\s*[:=]\\s*(.+?)\\s*$");
    private static final Pattern INLINE_KEY_VALUE = Pattern.compile("(?i)\\b([a-zA-Z][a-zA-Z0-9_-]{1,40})\\s*=\\s*([^,\\n]+)");
    private static final Pattern SOURCE_TYPE_YAML = Pattern.compile("(?is)source\\s*:\\s*.*?\\btype\\s*:\\s*([a-zA-Z0-9_-]+)");
    private static final Pattern TARGET_TYPE_YAML = Pattern.compile("(?is)target\\s*:\\s*.*?\\btype\\s*:\\s*([a-zA-Z0-9_-]+)");
    private static final Pattern SOURCE_TABLE_YAML = Pattern.compile("(?is)source\\s*:\\s*.*?resource\\s*:\\s*.*?\\bname\\s*:\\s*([^\\n#]+)");
    private static final Pattern TARGET_TABLE_YAML = Pattern.compile("(?is)target\\s*:\\s*.*?resource\\s*:\\s*.*?\\bname\\s*:\\s*([^\\n#]+)");
    private static final Pattern SOURCE_KEYS_BLOCK_YAML = Pattern.compile("(?is)comparison\\s*:\\s*.*?keys\\s*:\\s*.*?source\\s*:\\s*((?:\\n\\s*-\\s*[^\\n]+)+)");
    private static final Pattern TARGET_KEYS_BLOCK_YAML = Pattern.compile("(?is)comparison\\s*:\\s*.*?keys\\s*:\\s*.*?target\\s*:\\s*((?:\\n\\s*-\\s*[^\\n]+)+)");

    @Override
    public PendingQuestionState create(QuestionSpec spec) {
        PendingQuestionState.PendingQuestionStateBuilder builder = PendingQuestionState.builder()
                .question(spec.getQuestion())
                .originalRequest(spec.getOriginalRequest())
                .blocking(spec.isBlocking())
                .createdAt(Instant.now());
        if (spec.getExpectedKeys() != null) {
            spec.getExpectedKeys().forEach(builder::expectedKey);
        }
        return builder.build();
    }

    @Override
    public String merge(PendingQuestionState pendingQuestion, String answer) {
        if (pendingQuestion == null) {
            return answer;
        }
        String original = pendingQuestion.getOriginalRequest() == null ? "" : pendingQuestion.getOriginalRequest().trim();
        String rawSuffix = answer == null ? "" : answer.trim();
        String suffix = normalizeStructuredAnswer(rawSuffix, pendingQuestion.getExpectedKeys());
        if (original.isEmpty()) {
            return suffix;
        }
        if (suffix.isEmpty()) {
            return original;
        }
        if (!isStructuredPayload(suffix)
                && pendingQuestion.getExpectedKeys() != null
                && pendingQuestion.getExpectedKeys().size() == 1) {
            String expectedKey = pendingQuestion.getExpectedKeys().get(0);
            if (expectedKey != null && !expectedKey.isBlank()) {
                return original + System.lineSeparator() + expectedKey + ": " + suffix;
            }
        }
        if (isStructuredPayload(suffix)) {
            return original + System.lineSeparator() + suffix;
        }
        return original + System.lineSeparator() + "Clarification: " + suffix;
    }

    private String normalizeStructuredAnswer(String answer, List<String> expectedKeys) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        Map<String, String> values = new LinkedHashMap<>();
        extractKeyValuePairs(answer, values);
        extractYamlTemplateValues(answer, values);
        if (values.isEmpty()) {
            return answer.trim();
        }
        List<String> orderedKeys = orderedKeys(values, expectedKeys);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < orderedKeys.size(); i++) {
            String key = orderedKeys.get(i);
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append(key).append("=").append(value.trim());
        }
        return builder.length() == 0 ? answer.trim() : builder.toString();
    }

    private List<String> orderedKeys(Map<String, String> values, List<String> expectedKeys) {
        List<String> keys = new ArrayList<>();
        if (expectedKeys != null) {
            for (String expected : expectedKeys) {
                String canonical = canonicalKey(expected);
                if (canonical != null && values.containsKey(canonical) && !keys.contains(canonical)) {
                    keys.add(canonical);
                }
            }
        }
        for (String key : values.keySet()) {
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private void extractKeyValuePairs(String answer, Map<String, String> values) {
        Matcher lineMatcher = LINE_KEY_VALUE.matcher(answer);
        while (lineMatcher.find()) {
            putCanonical(values, lineMatcher.group(1), lineMatcher.group(2));
        }
        Matcher inlineMatcher = INLINE_KEY_VALUE.matcher(answer);
        while (inlineMatcher.find()) {
            putCanonical(values, inlineMatcher.group(1), inlineMatcher.group(2));
        }
    }

    private void extractYamlTemplateValues(String answer, Map<String, String> values) {
        if (answer == null || (!answer.contains("source:") && !answer.contains("target:") && !answer.contains("comparison:"))) {
            return;
        }
        putIfPresent(values, "sourceType", extractFirst(SOURCE_TYPE_YAML, answer));
        putIfPresent(values, "targetType", extractFirst(TARGET_TYPE_YAML, answer));
        putIfPresent(values, "sourceTable", extractFirst(SOURCE_TABLE_YAML, answer));
        putIfPresent(values, "targetTable", extractFirst(TARGET_TABLE_YAML, answer));
        List<String> sourceKeys = extractYamlList(SOURCE_KEYS_BLOCK_YAML, answer);
        List<String> targetKeys = extractYamlList(TARGET_KEYS_BLOCK_YAML, answer);
        if (!sourceKeys.isEmpty()) {
            putIfPresent(values, "sourceKeys", String.join(",", sourceKeys));
        }
        if (!targetKeys.isEmpty()) {
            putIfPresent(values, "targetKeys", String.join(",", targetKeys));
        }
        if (!sourceKeys.isEmpty() && sourceKeys.equals(targetKeys)) {
            putIfPresent(values, "keys", String.join(",", sourceKeys));
        }
    }

    private List<String> extractYamlList(Pattern blockPattern, String answer) {
        String block = extractFirst(blockPattern, answer);
        if (block == null || block.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?m)^\\s*-\\s*([^\\n#]+)").matcher(block);
        while (matcher.find()) {
            String value = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String extractFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        return value == null ? null : value.trim();
    }

    private void putCanonical(Map<String, String> values, String rawKey, String rawValue) {
        String key = canonicalKey(rawKey);
        if (key == null) {
            return;
        }
        putIfPresent(values, key, rawValue);
    }

    private void putIfPresent(Map<String, String> values, String key, String rawValue) {
        if (rawValue == null) {
            return;
        }
        String value = rawValue.trim();
        if (!value.isBlank()) {
            values.put(key, value);
        }
    }

    private String canonicalKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String key = rawKey.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").trim();
        switch (key) {
            case "sourcetype":
                return "sourceType";
            case "targettype":
                return "targetType";
            case "sourcetable":
            case "sourceresource":
                return "sourceTable";
            case "targettable":
            case "targetresource":
                return "targetTable";
            case "sourcequery":
                return "sourceQuery";
            case "targetquery":
                return "targetQuery";
            case "keys":
            case "key":
                return "keys";
            case "sourcekeys":
                return "sourceKeys";
            case "targetkeys":
                return "targetKeys";
            default:
                return null;
        }
    }

    private boolean isStructuredPayload(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("=") || (text.contains("\n") && text.contains(":"));
    }
}

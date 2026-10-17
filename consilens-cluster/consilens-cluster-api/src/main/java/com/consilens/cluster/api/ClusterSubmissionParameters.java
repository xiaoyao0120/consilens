package com.consilens.cluster.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing helpers shared by cluster submitter implementations for the
 * portable {@code properties} map of a submission request.
 */
public final class ClusterSubmissionParameters {

    private static final Pattern MEMORY_PATTERN = Pattern.compile("^(\\d+)\\s*([bkmgt]?)$",
            Pattern.CASE_INSENSITIVE);

    private ClusterSubmissionParameters() {
    }

    public static Map<String, Object> orEmpty(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    public static String orDefault(Map<String, Object> params, String key, String fallback) {
        Object value = params.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    public static String required(Map<String, Object> params, String key, String name) {
        Object value = params.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("cluster parameter is required: " + name);
        }
        return String.valueOf(value);
    }

    public static int intOrDefault(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key);
        return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    }

    public static Integer positiveInteger(Object value, String name) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        int parsed = Integer.parseInt(String.valueOf(value));
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    /** Parses memory like {@code 1g}, {@code 2048m} or {@code 512k} into MiB. */
    public static int parseMemoryMb(String value) {
        Matcher matcher = MEMORY_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid memory format: " + value + " (expected e.g. 1g, 2048m)");
        }
        long amount = Long.parseLong(matcher.group(1));
        switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "":  return Math.toIntExact(amount);
            case "b": return Math.toIntExact(Math.max(1, amount / (1024 * 1024)));
            case "k": return Math.toIntExact(Math.max(1, amount / 1024));
            case "m": return Math.toIntExact(amount);
            case "g": return Math.toIntExact(amount * 1024);
            case "t": return Math.toIntExact(amount * 1024 * 1024);
            default:  throw new IllegalArgumentException("invalid memory format: " + value);
        }
    }

    /** Parses CPU cores like {@code 0.5} into millicores. */
    public static int parseCpuMilli(String value) {
        return (int) Math.round(Double.parseDouble(value.trim()) * 1000);
    }

    public static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return result;
    }

    public static Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof Map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        ((Map<?, ?>) raw).forEach((key, value) -> {
            if (key != null && value != null) {
                result.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return result;
    }

    public static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

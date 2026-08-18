package com.consilens.agent.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Canonical JSON for digests (section 22.2): object keys sorted, numbers
 * normalized, nulls removed. Never hashed from Map.toString() or unsorted JSON.
 */
public final class CanonicalJsonDigester {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalJsonDigester() {
    }

    public static String canonical(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(canonicalize(node));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("cannot canonicalize node", e);
        }
    }

    public static String sha256(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String digest(JsonNode node) {
        return sha256(canonical(node));
    }

    public static JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(Comparator.naturalOrder());
            ObjectNode result = MAPPER.createObjectNode();
            for (String key : keys) {
                JsonNode value = canonicalize(node.get(key));
                if (value != null) {
                    result.set(key, value);
                }
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                JsonNode value = canonicalize(item);
                if (value != null) {
                    result.add(value);
                }
            }
            return result;
        }
        if (node.isNumber()) {
            return MAPPER.getNodeFactory().numberNode(node.decimalValue().stripTrailingZeros());
        }
        return node;
    }

    /**
     * Deterministic canonical form of a plain map (JSON boundary only).
     */
    public static String canonicalMap(java.util.Map<String, ?> map) {
        return canonical(MAPPER.valueToTree(map));
    }
}

package com.consilens.server.application.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Extracts individual field values from the encrypted secret payload (a JSON
 * object of field -> value). Falls back to the raw payload for legacy values.
 */
public final class SecretPayloadCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SecretPayloadCodec() {
    }

    public static String extract(char[] payload, String field) {
        try {
            JsonNode node = MAPPER.readTree(new String(payload));
            if (node.isObject()) {
                if (node.has(field)) {
                    return node.get(field).asText();
                }
                if (node.size() > 0) {
                    return node.fields().next().getValue().asText();
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // fall through to raw payload
        }
        return new String(payload);
    }
}

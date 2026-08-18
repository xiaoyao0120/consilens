package com.consilens.agent.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CanonicalJsonDigesterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keyOrderDoesNotChangeTheDigest() throws Exception {
        ObjectNode first = mapper.createObjectNode();
        first.put("host", "prod-db").put("port", 3306);
        ObjectNode second = mapper.createObjectNode();
        second.put("port", 3306).put("host", "prod-db");

        assertEquals(CanonicalJsonDigester.digest(first), CanonicalJsonDigester.digest(second));
        assertNotEquals(CanonicalJsonDigester.digest(first),
                CanonicalJsonDigester.digest(mapper.createObjectNode().put("host", "prod-db").put("port", 3307)));
    }

    @Test
    void numericValuesAreNormalized() throws Exception {
        ObjectNode one = mapper.createObjectNode();
        one.put("port", 3306);
        ObjectNode oneFloat = mapper.createObjectNode();
        oneFloat.put("port", 3306.0);

        assertEquals(CanonicalJsonDigester.digest(one), CanonicalJsonDigester.digest(oneFloat));
    }

    @Test
    void nullFieldsAreRemoved() throws Exception {
        ObjectNode withNull = mapper.createObjectNode();
        withNull.put("host", "h");
        withNull.putNull("password");
        ObjectNode clean = mapper.createObjectNode();
        clean.put("host", "h");

        assertEquals(CanonicalJsonDigester.digest(withNull), CanonicalJsonDigester.digest(clean));
    }

    @Test
    void digestIsSha256Hex() {
        String digest = CanonicalJsonDigester.digest(mapper.createObjectNode().put("a", 1));
        assertEquals(64, digest.length());
        assertEquals(digest, digest.toLowerCase());
    }
}

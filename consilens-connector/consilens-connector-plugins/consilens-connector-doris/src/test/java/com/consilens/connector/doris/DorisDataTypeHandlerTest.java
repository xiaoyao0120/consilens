package com.consilens.connector.doris;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DorisDataTypeHandler.
 */
class DorisDataTypeHandlerTest {

    private DorisDataTypeHandler handler;
    private DorisCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new DorisCapabilityProvider();
        handler = new DorisDataTypeHandler(capabilityProvider);
    }

    @Test
    void testNormalizeColumn_Int() {
        String result = handler.normalizeColumn("age", DataType.INTEGER);
        assertEquals("COALESCE(TRIM(CAST(`age` AS CHAR)), '0')", result);
    }

    @Test
    void testNormalizeColumn_Timestamp() {
        String result = handler.normalizeColumn("created_at", DataType.TIMESTAMP);
        assertEquals(
                "COALESCE(DATE_FORMAT(CONVERT_TZ(`created_at`, @@session.time_zone, '+00:00'), '%Y-%m-%d %H:%i:%s'), '')",
                result);
    }

    @Test
    void testNormalizeColumn_Boolean() {
        String result = handler.normalizeColumn("is_active", DataType.BOOLEAN);
        assertTrue(result.contains("CASE"));
        assertTrue(result.contains("'1'"));
        assertTrue(result.contains("'0'"));
    }

    @Test
    void testNormalizeDecimalCarriesRoundingIntoIntegerPart() {
        String result = handler.normalizeColumn("amount", DataType.DECIMAL);
        // The whole rounded value is cast to a fixed-scale DECIMAL, so rounding
        // carry (e.g. -1.99999 with precision 4 becomes -2.0000, not -1.10000)
        // propagates into the integer part. ROUND must apply directly to the DECIMAL
        // column: a DOUBLE detour would lose precision for large DECIMAL(38, N) values
        // and diverge from MySQL FORMAT(ROUND(col, N), N).
        assertTrue(result.contains("ROUND(`amount`, 4)"));
        assertTrue(result.contains("AS DECIMAL(38, 4)"));
        assertTrue(result.contains("'0.0000'"));
        assertFalse(result.contains("Math.pow"));
        assertFalse(result.contains("FLOOR(ABS"));
        assertFalse(result.contains("AS DOUBLE"));
    }

    @Test
    void testNormalizeDecimalCapsPrecisionToDecimalLimit() {
        Map<String, Object> config = new HashMap<>();
        config.put("decimal", new DorisDecimalRule(38));
        DorisDataTypeHandler configured = new DorisDataTypeHandler(capabilityProvider, config);

        String result = configured.normalizeColumn("amount", DataType.DECIMAL);
        // precision > 30 must be capped to keep DECIMAL(38, scale) valid instead of
        // overflowing (long) Math.pow(10, precision).
        assertTrue(result.contains("AS DECIMAL(38, 30)"));
    }

    /** Minimal normalization rule with a getPrecision() method for test configs. */
    public static class DorisDecimalRule {
        private final int precision;

        public DorisDecimalRule(int precision) {
            this.precision = precision;
        }

        public int getPrecision() {
            return precision;
        }
    }

    @Test
    void testGetDataTypeMappingVarchar() {
        assertEquals("VARCHAR(100)", handler.getDataTypeMapping("varchar", 100, 0, 0));
        assertEquals("VARCHAR(255)", handler.getDataTypeMapping("varchar", 0, 0, 0));
    }

    @Test
    void testGetDataTypeMappingDecimal() {
        assertEquals("DECIMAL(10,2)", handler.getDataTypeMapping("decimal", 0, 10, 2));
        assertEquals("DECIMAL(10)", handler.getDataTypeMapping("decimal", 0, 10, 0));
        assertEquals("DECIMAL", handler.getDataTypeMapping("decimal", 0, 0, 0));
    }

    @Test
    void testGetDataTypeMappingInteger() {
        assertEquals("INT", handler.getDataTypeMapping("int", 0, 0, 0));
        assertEquals("BIGINT", handler.getDataTypeMapping("bigint", 0, 0, 0));
        assertEquals("TINYINT", handler.getDataTypeMapping("tinyint", 0, 0, 0));
    }

    @Test
    void testGetDataTypeMappingBoolean() {
        assertEquals("TINYINT(1)", handler.getDataTypeMapping("boolean", 0, 0, 0));
        assertEquals("TINYINT(1)", handler.getDataTypeMapping("bool", 0, 0, 0));
    }

    @Test
    void testGetDataTypeMappingJSON() {
        assertEquals("JSON", handler.getDataTypeMapping("json", 0, 0, 0));
    }

    @Test
    void shouldConvertArrayDescriptor() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("ARRAY<INT>");

        assertEquals(com.consilens.common.enums.DataType.ARRAY_TYPE, descriptor.getType());
        assertEquals(com.consilens.common.enums.DataType.INTEGER_TYPE, descriptor.getElementType().getType());
        assertEquals("ARRAY<INT>", handler.convertToOriginType(descriptor));
    }
}

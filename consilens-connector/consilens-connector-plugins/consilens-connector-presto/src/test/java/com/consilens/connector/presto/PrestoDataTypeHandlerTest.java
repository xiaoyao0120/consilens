package com.consilens.connector.presto;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrestoDataTypeHandlerTest {

    private PrestoDataTypeHandler handler;
    private PrestoCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new PrestoCapabilityProvider();
        handler = new PrestoDataTypeHandler(capabilityProvider);
    }

    @Test
    void testNormalizeColumnNumeric() {
        String result = handler.normalizeColumn("amount", DataType.INTEGER);
        assertEquals("COALESCE(TRIM(CAST(\"amount\" AS VARCHAR)), '0')", result);
    }

    @Test
    void testNormalizeColumn_Date() {
        String result = handler.normalizeColumn("created_at", DataType.DATE);
        assertTrue(result.contains("FORMAT_DATETIME"));
        assertTrue(result.contains("yyyy-MM-dd"));
    }

    @Test
    void testNormalizeDecimalKeepsTrailingZeros() {
        String result = handler.normalizeColumn("amount", DataType.DECIMAL);
        // Fixed-decimal format() keeps trailing zeros (1.5 -> 1.5000) so DOUBLE and
        // DECIMAL checksums match MySQL's FORMAT(col, N) output.
        assertTrue(result.contains("FORMAT('%."));
        assertTrue(result.contains("4f'"));
        assertTrue(result.contains("'0.0000'"));
        assertFalse(result.contains("AS VARCHAR)), '0.0000'"));
    }

    @Test
    void testNormalizeFloatKeepsTrailingZeros() {
        String result = handler.normalizeColumn("score", DataType.FLOAT);
        assertTrue(result.contains("FORMAT('%."));
        assertTrue(result.contains("CAST(\"score\" AS DOUBLE)"));
    }

    @Test
    void testNormalizeDoubleKeepsTrailingZeros() {
        String result = handler.normalizeColumn("ratio", DataType.DOUBLE);
        assertTrue(result.contains("FORMAT('%."));
        assertFalse(result.contains("CAST(ROUND(\"ratio\""));
    }

    @Test
    void testGetDataTypeMappingVarchar() {
        String result = handler.getDataTypeMapping("varchar", 255, 0, 0);
        assertEquals("VARCHAR(255)", result);
    }

    @Test
    void testGetDataTypeMappingDecimal() {
        String result = handler.getDataTypeMapping("decimal", 0, 10, 2);
        assertEquals("DECIMAL(10,2)", result);
    }

    @Test
    void testGetDataTypeMappingInteger() {
        String result = handler.getDataTypeMapping("integer", 0, 0, 0);
        assertEquals("INTEGER", result);
    }

    @Test
    void testGetDataTypeMappingBoolean() {
        String result = handler.getDataTypeMapping("boolean", 0, 0, 0);
        assertEquals("BOOLEAN", result);
    }

    @Test
    void testGetDataTypeMappingJSON() {
        String result = handler.getDataTypeMapping("json", 0, 0, 0);
        assertEquals("JSON", result);
    }

    @Test
    void shouldConvertRowDescriptor() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("ROW(id BIGINT, name VARCHAR)");

        assertEquals(com.consilens.common.enums.DataType.STRUCT_TYPE, descriptor.getType());
        assertEquals(2, descriptor.getFields().size());
        assertEquals("ROW(id BIGINT, name VARCHAR)", handler.convertToOriginType(descriptor));
    }
}

package com.consilens.conncetor.base.write;

import com.consilens.common.enums.DataType;
import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.write.PreparedWriteRow;
import com.consilens.connector.api.write.TableWritePlan;
import com.consilens.connector.api.write.TypedOutputRow;
import com.consilens.connector.api.write.TypedOutputValue;
import com.consilens.connector.api.write.WriteColumnSpec;
import com.consilens.connector.api.model.ConnectorNativeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class JdbcTableWriteCompilerTest {

    @Test
    void binaryValuesStayBytesInsteadOfObjectToString() {
        TypeDescriptor binary = TypeDescriptor.builder(DataType.BINARY_TYPE).build();
        WriteColumnSpec column = new WriteColumnSpec("payload", binary, binary,
                ConnectorNativeType.builder().jdbcType(java.sql.Types.BINARY).build(), true);
        TableWritePlan plan = new TableWritePlan("mysql", null, null, null, List.of(column));
        byte[] payload = new byte[]{1, 2, 3};

        PreparedWriteRow row = new JdbcTableWriteCompiler(null).prepareRow(
                new TypedOutputRow(List.of(new TypedOutputValue("payload", binary, payload))), plan);

        assertArrayEquals(payload, (byte[]) row.getValues().get(0).getValue());
    }
}

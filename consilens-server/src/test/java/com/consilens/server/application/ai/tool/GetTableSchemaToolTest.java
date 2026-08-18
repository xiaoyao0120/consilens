package com.consilens.server.application.ai.tool;

import com.consilens.server.api.dto.MetadataColumnDto;
import com.consilens.server.application.ai.tool.dto.GetTableSchemaInput;
import com.consilens.server.application.ai.tool.dto.GetTableSchemaOutput;
import com.consilens.server.application.datasource.DataSourceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * get_table_schema 必须返回列结构并把主键列标记出来。
 */
public class GetTableSchemaToolTest {

    private final DataSourceService service = mock(DataSourceService.class);
    private final GetTableSchemaTool tool = new GetTableSchemaTool(service);

    @Test
    public void returnsColumnsAndFlagsPrimaryKeys() {
        when(service.getColumns(7L, "db", "t")).thenReturn(List.of(
                MetadataColumnDto.builder().name("id").dataType("int").nullable(false).build(),
                MetadataColumnDto.builder().name("name").dataType("varchar").nullable(true).build()));
        when(service.getPrimaryKeys(7L, "db", "t")).thenReturn(List.of("id"));

        GetTableSchemaOutput output = tool.execute(
                GetTableSchemaInput.builder().datasourceId("7").database("db").table("t").build(),
                new DefaultTestToolContext("actor", "s1")).getStructuredData();

        assertEquals(2, output.getColumns().size());
        assertTrue(output.getColumns().get(0).isPrimaryKey());
        assertEquals(false, output.getColumns().get(1).isPrimaryKey());
        assertEquals(List.of("id"), output.getPrimaryKeys());
    }
}

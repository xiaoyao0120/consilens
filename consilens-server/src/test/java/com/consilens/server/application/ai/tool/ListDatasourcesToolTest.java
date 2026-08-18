package com.consilens.server.application.ai.tool;

import com.consilens.server.api.dto.DataSourceDto;
import com.consilens.server.application.ai.tool.dto.ListDatasourcesInput;
import com.consilens.server.application.ai.tool.dto.ListDatasourcesOutput;
import com.consilens.server.application.datasource.DataSourceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * list_datasources 必须支持关键词过滤、不含密码、结果截断。
 */
public class ListDatasourcesToolTest {

    private final DataSourceService service = mock(DataSourceService.class);
    private final ListDatasourcesTool tool = new ListDatasourcesTool(service);

    @Test
    public void keywordFiltersByNameAndNeverExposesPassword() {
        when(service.list()).thenReturn(List.of(
                DataSourceDto.builder()
                        .id("1").name("orders_mysql").type("mysql")
                        .param(Map.of("host", "h", "database", "orders_db", "password", "secret"))
                        .build(),
                DataSourceDto.builder()
                        .id("2").name("inventory_pg").type("postgresql")
                        .param(Map.of("database", "inv"))
                        .build()));

        ListDatasourcesOutput output = tool.execute(
                ListDatasourcesInput.builder().keyword("orders").build(),
                new DefaultTestToolContext("actor", "s1")).getStructuredData();

        assertEquals(1, output.getDatasources().size());
        assertEquals("orders_mysql", output.getDatasources().get(0).getName());
        assertEquals("orders_db", output.getDatasources().get(0).getDatabase());
        assertFalse(output.isTruncated());
        assertTrue(output.getDatasources().get(0).toString().contains("secret") == false);
    }

    @Test
    public void emptyKeywordReturnsAllAndTruncatesAtLimit() {
        java.util.List<DataSourceDto> all = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            all.add(DataSourceDto.builder()
                    .id(String.valueOf(i)).name("ds" + i)
                    .type("mysql").param(Map.of())
                    .build());
        }
        when(service.list()).thenReturn(all);

        ListDatasourcesOutput output = tool.execute(
                ListDatasourcesInput.builder().build(),
                new DefaultTestToolContext("actor", "s1")).getStructuredData();

        assertEquals(50, output.getDatasources().size());
        assertTrue(output.isTruncated());
    }
}

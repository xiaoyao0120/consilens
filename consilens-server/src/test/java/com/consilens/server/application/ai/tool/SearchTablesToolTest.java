package com.consilens.server.application.ai.tool;

import com.consilens.server.api.dto.DataSourceDto;
import com.consilens.server.application.ai.tool.dto.SearchTablesInput;
import com.consilens.server.application.ai.tool.dto.SearchTablesOutput;
import com.consilens.server.application.datasource.DataSourceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * search_tables 必须按关键词返回跨数据源候选、支持限定数据源、
 * 单个数据源失败不能拖垮整体。
 */
public class SearchTablesToolTest {

    private final DataSourceService service = mock(DataSourceService.class);
    private final SearchTablesTool tool = new SearchTablesTool(service);

    @Test
    public void keywordMatchesTableAndDatabaseCaseInsensitively() {
        when(service.list()).thenReturn(List.of(
                DataSourceDto.builder().id("1").name("ds1").type("mysql").build()));
        when(service.getDatabases(1L)).thenReturn(List.of("orders_db", "archive"));
        when(service.getTables(1L, "orders_db")).thenReturn(List.of("t_order", "t_customer"));
        when(service.getTables(1L, "archive")).thenReturn(List.of("ORDERS_2024"));

        SearchTablesOutput output = tool.execute(
                SearchTablesInput.builder().keyword("order").build(),
                new DefaultTestToolContext("actor", "s1")).getStructuredData();

        assertEquals(2, output.getCandidates().size());
        assertTrue(output.getCandidates().stream().anyMatch(c ->
                c.getDatabase().equals("orders_db") && c.getTable().equals("t_order")));
        assertTrue(output.getCandidates().stream().anyMatch(c ->
                c.getDatabase().equals("archive") && c.getTable().equals("ORDERS_2024")));
        assertTrue(output.getSkippedDatasources().isEmpty());
    }

    @Test
    public void failingDatasourceIsSkippedAndOthersStillReturned() {
        when(service.list()).thenReturn(List.of(
                DataSourceDto.builder().id("1").name("broken").type("mysql").build(),
                DataSourceDto.builder().id("2").name("healthy").type("mysql").build()));
        when(service.getDatabases(1L)).thenThrow(new IllegalStateException("conn refused"));
        when(service.getDatabases(2L)).thenReturn(List.of("db"));
        when(service.getTables(2L, "db")).thenReturn(List.of("order_tb"));

        SearchTablesOutput output = tool.execute(
                SearchTablesInput.builder().keyword("order").build(),
                new DefaultTestToolContext("actor", "s1")).getStructuredData();

        assertEquals(1, output.getCandidates().size());
        assertEquals("order_tb", output.getCandidates().get(0).getTable());
        assertEquals(List.of("broken"), output.getSkippedDatasources());
    }

    @Test
    public void datasourceIdScopeLimitsSearch() {
        when(service.list()).thenReturn(List.of(
                DataSourceDto.builder().id("1").name("ds1").type("mysql").build(),
                DataSourceDto.builder().id("2").name("ds2").type("mysql").build()));
        when(service.get(2L)).thenReturn(DataSourceDto.builder().id("2").name("ds2").type("mysql").build());
        when(service.getDatabases(2L)).thenReturn(List.of("db"));
        when(service.getTables(2L, "db")).thenReturn(List.of("order_tb"));

        SearchTablesOutput output = tool.execute(
                SearchTablesInput.builder().keyword("order").datasourceId("2").build(),
                new DefaultTestToolContext("actor", "s1")).getStructuredData();

        assertEquals(1, output.getCandidates().size());
        assertEquals("ds2", output.getCandidates().get(0).getDatasourceName());
    }
}

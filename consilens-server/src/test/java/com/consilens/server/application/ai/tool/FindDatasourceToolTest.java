package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.server.api.dto.DataSourceDto;
import com.consilens.server.application.ai.tool.dto.FindDatasourceInput;
import com.consilens.server.application.ai.tool.dto.FindDatasourceOutput;
import com.consilens.server.application.datasource.DataSourceService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FindDatasourceToolTest {

    private final DataSourceService service = mock(DataSourceService.class);
    private final FindDatasourceTool tool = new FindDatasourceTool(service);

    @Test
    void returnsFoundDatasourceWithoutSecrets() {
        when(service.findByName("prod_mysql")).thenReturn(Optional.of(
                DataSourceDto.builder()
                        .id("12").name("prod_mysql").type("mysql")
                        .param(Map.of("host", "prod-db", "password", "***"))
                        .build()));

        AgentToolOutcome<FindDatasourceOutput> outcome = tool.execute(
                FindDatasourceInput.builder().name("prod_mysql").build(), null);

        assertTrue(outcome.isSuccess());
        assertTrue(outcome.getStructuredData().isFound());
        assertEquals("12", outcome.getStructuredData().getId());
        assertEquals("prod_mysql", outcome.getStructuredData().getName());
        verify(service, times(1)).findByName("prod_mysql");
    }

    @Test
    void missingNameReportsNotFound() {
        when(service.findByName("missing")).thenReturn(Optional.empty());
        AgentToolOutcome<FindDatasourceOutput> outcome = tool.execute(
                FindDatasourceInput.builder().name("missing").build(), null);
        assertTrue(outcome.isSuccess());
        assertFalse(outcome.getStructuredData().isFound());
    }
}

package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.server.api.dto.DataSourceTypeDto;
import com.consilens.server.application.ai.tool.dto.ListDatasourceTypesInput;
import com.consilens.server.application.ai.tool.dto.ListDatasourceTypesOutput;
import com.consilens.server.application.datasource.DataSourceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListDatasourceTypesToolTest {

    private final DataSourceService service = mock(DataSourceService.class);
    private final ListDatasourceTypesTool tool = new ListDatasourceTypesTool(service);

    @Test
    void listsTypesWithoutExposingDriverClass() {
        when(service.listTypes()).thenReturn(List.of(
                DataSourceTypeDto.builder().type("mysql").defaultPort(3306).driverClass("com.mysql.Driver").build(),
                DataSourceTypeDto.builder().type("starrocks").defaultPort(9030).driverClass("x.Driver").build()));

        AgentToolOutcome<ListDatasourceTypesOutput> outcome = tool.execute(
                new ListDatasourceTypesInput(), null);

        assertTrue(outcome.isSuccess());
        assertEquals(2, outcome.getStructuredData().getTypes().size());
        assertEquals("mysql", outcome.getStructuredData().getTypes().get(0).getType());
        assertEquals(3306, outcome.getStructuredData().getTypes().get(0).getDefaultPort());
        assertFalse(outcome.getContent().contains("com.mysql.Driver"));
        verify(service, times(1)).listTypes();
    }

    @Test
    void noDialectsFailsWithDomainError() {
        when(service.listTypes()).thenReturn(List.of());
        AgentToolOutcome<ListDatasourceTypesOutput> outcome = tool.execute(
                new ListDatasourceTypesInput(), null);
        assertFalse(outcome.isSuccess());
        assertEquals("NO_DATASOURCE_TYPE_AVAILABLE", outcome.getErrorCode());
    }

    @Test
    void reduceIsANoOp() {
        AgentWorkingState state = AgentWorkingState.builder()
                .objectiveId("o1").objective("obj").stage(AgentWorkflowStage.DISCOVERY).build();
        assertSame(state, tool.reduce(state, ListDatasourceTypesOutput.builder().types(List.of()).build()));
    }
}

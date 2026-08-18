package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.connector.api.DataSourceField;
import com.consilens.server.application.ai.tool.dto.GetDatasourceFormSchemaInput;
import com.consilens.server.application.ai.tool.dto.GetDatasourceFormSchemaOutput;
import com.consilens.server.application.datasource.DataSourceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetDatasourceFormSchemaToolTest {

    private final DataSourceService service = mock(DataSourceService.class);
    private final GetDatasourceFormSchemaTool tool = new GetDatasourceFormSchemaTool(service);

    @Test
    void mapsFormFieldsAndMarksSensitiveByNameAndFlag() {
        when(service.getTypeConfig("mysql")).thenReturn(List.of(
                DataSourceField.builder().field("host").title("主机").type("input").required(true).build(),
                DataSourceField.builder().field("password").title("密码").type("input").required(true).build(),
                DataSourceField.builder().field("token").title("Token").sensitive(true).build()));

        AgentToolOutcome<GetDatasourceFormSchemaOutput> outcome = tool.execute(
                GetDatasourceFormSchemaInput.builder().type("mysql").build(), null);

        assertTrue(outcome.isSuccess());
        List<com.consilens.server.application.ai.tool.dto.DatasourceFormField> fields =
                outcome.getStructuredData().getFields();
        assertEquals(3, fields.size());
        assertFalse(fields.get(0).isSensitive());
        assertTrue(fields.get(1).isSensitive());
        assertTrue(fields.get(2).isSensitive());
        assertTrue(fields.get(0).isRequired());
        verify(service, times(1)).getTypeConfig("mysql");
    }

    @Test
    void unsupportedTypeMapsToDomainError() {
        when(service.getTypeConfig("nope")).thenThrow(new IllegalArgumentException("unsupported"));
        AgentToolOutcome<GetDatasourceFormSchemaOutput> outcome = tool.execute(
                GetDatasourceFormSchemaInput.builder().type("nope").build(), null);
        assertFalse(outcome.isSuccess());
        assertEquals("DATASOURCE_PROBE_UNSUPPORTED", outcome.getErrorCode());
        assertFalse(outcome.isRetryable());
    }
}

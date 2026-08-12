package com.consilens.server.application.datasource;

import com.consilens.server.api.dto.ConnectionTestRequest;
import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.api.dto.DataSourceCreateRequest;
import com.consilens.server.api.dto.DataSourceDto;
import com.consilens.server.api.dto.DataSourceTypeDto;
import com.consilens.server.api.dto.MetadataColumnDto;
import com.consilens.server.application.connection.ConnectionTestService;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.repository.DataSourceRepository;
import com.consilens.server.support.crypto.CryptoSupport;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.MetadataQueryGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDataSourceServiceTest {

    private static final Long DS_ID = 1L;

    private DataSourceRepository repository;
    private DialectSupport dialectSupport;
    private ConnectionTestService connectionTestService;
    private DatabaseDialect dialect;
    private MetadataQueryGenerator metadataQueryGenerator;
    private DataSourceServiceImpl service;
    private DataSourceServiceImpl.MetadataConnectionOpener opener;

    @BeforeEach
    void setUp() {
        repository = mock(DataSourceRepository.class);
        dialectSupport = mock(DialectSupport.class);
        connectionTestService = mock(ConnectionTestService.class);
        dialect = mock(DatabaseDialect.class);
        metadataQueryGenerator = mock(MetadataQueryGenerator.class);
        opener = mock(DataSourceServiceImpl.MetadataConnectionOpener.class);
        service = new DataSourceServiceImpl(repository, dialectSupport, connectionTestService,
                new ObjectMapper(), new CryptoSupport(""), opener);
        when(dialect.getConnectorType()).thenReturn("mysql");
        when(dialect.getMetadataQueryGenerator()).thenReturn(metadataQueryGenerator);
        when(dialectSupport.find("mysql")).thenReturn(Optional.of(dialect));
    }

    @Test
    void shouldListTypesFromSpi() {
        when(dialectSupport.listTypes()).thenReturn(List.of(
                DataSourceTypeDto.builder().type("mysql").defaultPort(3306)
                        .driverClass("com.mysql.cj.jdbc.Driver").build()));

        List<DataSourceTypeDto> types = service.listTypes();

        assertEquals(1, types.size());
        assertEquals("mysql", types.get(0).getType());
        assertEquals(3306, types.get(0).getDefaultPort());
    }

    @Test
    void shouldCreateDataSource() {
        when(repository.save(any())).thenAnswer(invocation -> {
            DataSourceRecord record = invocation.getArgument(0);
            record.setId(DS_ID);
            record.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            record.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            return record;
        });

        DataSourceDto dto = service.create(DataSourceCreateRequest.builder()
                .name("orders-db")
                .type("mysql")
                .param(Map.of("host", "10.0.0.5", "port", 3306, "database", "orders"))
                .build());

        assertEquals("1", dto.getId());
        assertEquals("orders-db", dto.getName());
        assertEquals("mysql", dto.getType());
        ArgumentCaptor<DataSourceRecord> captor = ArgumentCaptor.forClass(DataSourceRecord.class);
        verify(repository).save(captor.capture());
        assertTrue(captor.getValue().getParamJson().contains("\"host\":\"10.0.0.5\""));
    }

    @Test
    void shouldRejectDuplicateName() {
        when(repository.findByName("dup")).thenReturn(Optional.of(DataSourceRecord.builder().id(9L).build()));

        assertThrows(IllegalArgumentException.class, () -> service.create(
                DataSourceCreateRequest.builder().name("dup").type("mysql").build()));
    }

    @Test
    void shouldExposeParamWithoutPassword() {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(DS_ID).name("orders-db").type("mysql")
                .paramJson("{\"host\":\"10.0.0.5\",\"port\":3306,\"database\":\"orders\","
                        + "\"username\":\"root\",\"password\":\"secret\"}")
                .build()));

        DataSourceDto dto = service.get(DS_ID);

        assertTrue(dto.getParam().containsKey("host"));
        assertEquals("10.0.0.5", dto.getParam().get("host"));
        assertEquals(3306, dto.getParam().get("port"));
        assertTrue(!dto.getParam().containsKey("password"));
    }

    @Test
    void shouldGetAndList() {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));
        when(repository.listAll()).thenReturn(List.of(record()));

        assertEquals("orders-db", service.get(DS_ID).getName());
        assertEquals(1, service.list().size());
    }

    @Test
    void shouldThrowNotFoundWhenMissing() {
        when(repository.findById(DS_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.get(DS_ID));
        assertThrows(ResourceNotFoundException.class, () -> service.test(DS_ID));
        assertThrows(ResourceNotFoundException.class, () -> service.getDatabases(DS_ID));
    }

    @Test
    void shouldDelete() {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));

        service.delete(DS_ID);

        verify(repository).deleteById(DS_ID);
    }

    @Test
    void shouldTestUsingStoredParams() {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));
        when(connectionTestService.test(any())).thenReturn(ConnectionTestResponse.builder()
                .success(true).latencyMs(10L).build());

        ConnectionTestResponse response = service.test(DS_ID);

        assertTrue(response.isSuccess());
        ArgumentCaptor<ConnectionTestRequest> captor = ArgumentCaptor.forClass(ConnectionTestRequest.class);
        verify(connectionTestService).test(captor.capture());
        ConnectionTestRequest request = captor.getValue();
        assertEquals("mysql", request.getType());
        assertEquals("10.0.0.5", request.getHost());
        assertEquals(3306, request.getPort());
        assertEquals("orders", request.getDatabase());
        assertEquals("admin", request.getUsername());
        assertEquals("secret", request.getPassword());
    }

    @Test
    void shouldListDatabases() throws Exception {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));
        when(metadataQueryGenerator.getSchemasSQL()).thenReturn("SELECT schema_name FROM information_schema.schemata");
        Connection connection = mockConnection("db1", "db2");
        when(opener.open(eq(dialect), any())).thenReturn(connection);

        List<String> databases = service.getDatabases(DS_ID);

        assertEquals(List.of("db1", "db2"), databases);
        verify(opener).open(eq(dialect), any());
    }

    @Test
    void shouldListTables() throws Exception {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));
        when(metadataQueryGenerator.getTablesSQL("orders")).thenReturn("SELECT table_name FROM ...");
        Connection connection = mockConnection("t1", "t2");
        when(opener.open(eq(dialect), any())).thenReturn(connection);

        List<String> tables = service.getTables(DS_ID, "orders");

        assertEquals(List.of("t1", "t2"), tables);
        verify(metadataQueryGenerator).getTablesSQL("orders");
    }

    @Test
    void shouldListColumns() throws Exception {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));
        when(metadataQueryGenerator.getTableColumnsSQL("orders", "t1")).thenReturn("SELECT ...");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString(1)).thenReturn("id", "name");
        when(resultSet.getString(2)).thenReturn("bigint", "varchar");
        when(resultSet.getString(3)).thenReturn("NO", "YES");
        when(opener.open(eq(dialect), any())).thenReturn(connection);

        List<MetadataColumnDto> columns = service.getColumns(DS_ID, "orders", "t1");

        assertEquals(2, columns.size());
        assertEquals("id", columns.get(0).getName());
        assertEquals("bigint", columns.get(0).getDataType());
        assertEquals(false, columns.get(0).getNullable());
        assertEquals("name", columns.get(1).getName());
        assertEquals(true, columns.get(1).getNullable());
    }

    @Test
    void shouldFailMetadataWhenConnectionFails() throws Exception {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));
        when(opener.open(eq(dialect), any()))
                .thenThrow(new IllegalStateException("Connection refused: 10.0.0.5:3306"));

        assertThrows(IllegalStateException.class, () -> service.getDatabases(DS_ID));
    }

    @Test
    void shouldFailForUnsupportedTypeInMetadata() throws Exception {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(DS_ID).name("x").type("unknown")
                .paramJson("{\"host\":\"8.8.8.8\"}")
                .build()));
        when(dialectSupport.find("unknown")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.getDatabases(DS_ID));
        verify(opener, never()).open(any(), any());
    }

    @Test
    void shouldEncryptPasswordWhenKeyConfigured() {
        CryptoSupport crypto = new CryptoSupport(java.util.Base64.getEncoder()
                .encodeToString(new byte[32]));
        DataSourceServiceImpl encryptedService = new DataSourceServiceImpl(repository,
                dialectSupport, connectionTestService, new ObjectMapper(), crypto, opener);
        when(repository.save(any())).thenAnswer(invocation -> {
            DataSourceRecord record = invocation.getArgument(0);
            record.setId(DS_ID);
            return record;
        });

        encryptedService.create(DataSourceCreateRequest.builder()
                .name("secure-db")
                .type("mysql")
                .param(Map.of("host", "10.0.0.5", "password", "secret"))
                .build());

        ArgumentCaptor<DataSourceRecord> captor = ArgumentCaptor.forClass(DataSourceRecord.class);
        verify(repository).save(captor.capture());
        String stored = captor.getValue().getParamJson();
        assertTrue(stored.contains("host\":\"10.0.0.5"));
        assertTrue(stored.contains("password\":\""));
        assertTrue(!stored.contains("\"password\":\"secret\""));
    }

    @Test
    void shouldRejectInvalidParamHost() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(IllegalArgumentException.class, () -> service.create(
                DataSourceCreateRequest.builder().name("x").type("mysql")
                        .param(Map.of("host", ""))
                        .build()));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                DataSourceCreateRequest.builder().name("y").type("mysql")
                        .param(Map.of("host", "bad host!"))
                        .build()));
    }

    @Test
    void shouldRejectInvalidParamDatabase() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(IllegalArgumentException.class, () -> service.create(
                DataSourceCreateRequest.builder().name("z").type("mysql")
                        .param(Map.of("host", "10.0.0.5", "database", "db?ssl=true"))
                        .build()));
    }

    @Test
    void shouldDecryptPasswordWhenTestingEncryptedDataSource() {
        CryptoSupport crypto = new CryptoSupport(java.util.Base64.getEncoder()
                .encodeToString(new byte[32]));
        DataSourceServiceImpl encryptedService = new DataSourceServiceImpl(repository,
                dialectSupport, connectionTestService, new ObjectMapper(), crypto, opener);
        String encrypted = crypto.protect("s3cret");
        when(repository.findById(DS_ID)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(DS_ID).name("secure").type("mysql")
                .paramJson("{\"host\":\"10.0.0.5\",\"port\":3306,\"database\":\"orders\","
                        + "\"password\":\"" + encrypted + "\"}")
                .build()));
        when(connectionTestService.test(any())).thenReturn(ConnectionTestResponse.builder()
                .success(true).latencyMs(5L).build());

        encryptedService.test(DS_ID);

        ArgumentCaptor<ConnectionTestRequest> captor = ArgumentCaptor.forClass(ConnectionTestRequest.class);
        verify(connectionTestService).test(captor.capture());
        assertEquals("s3cret", captor.getValue().getPassword());
    }

    @Test
    void shouldKeepPasswordWhenUpdatingWithBlankPassword() {
        CryptoSupport crypto = new CryptoSupport(java.util.Base64.getEncoder()
                .encodeToString(new byte[32]));
        DataSourceServiceImpl encryptedService = new DataSourceServiceImpl(repository,
                dialectSupport, connectionTestService, new ObjectMapper(), crypto, opener);
        when(repository.findById(DS_ID)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(DS_ID).name("secure").type("mysql")
                .paramJson("{\"host\":\"10.0.0.5\",\"password\":\"" + crypto.protect("old-pass") + "\"}")
                .build()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        encryptedService.update(DS_ID, DataSourceCreateRequest.builder()
                .name("secure").type("mysql")
                .param(Map.of("host", "10.0.0.6"))
                .build());

        ArgumentCaptor<DataSourceRecord> captor = ArgumentCaptor.forClass(DataSourceRecord.class);
        verify(repository).save(captor.capture());
        String stored = captor.getValue().getParamJson();
        // 保留的旧密码被重新加密存储（enc: 前缀且非明文）
        assertTrue(stored.contains("enc:"));
        assertTrue(!stored.contains("old-pass"));
    }

    @Test
    void shouldPassThroughAlreadyEncryptedValue() {
        CryptoSupport crypto = new CryptoSupport(java.util.Base64.getEncoder()
                .encodeToString(new byte[32]));
        String encrypted = crypto.protect("secret");

        // 已带 enc: 前缀的值不再二次加密（客户端回传场景）
        assertEquals(encrypted, crypto.protect(encrypted));
    }

    private DataSourceRecord record() {
        return DataSourceRecord.builder()
                .id(DS_ID)
                .name("orders-db")
                .type("mysql")
                .paramJson("{\"host\":\"10.0.0.5\",\"port\":3306,\"database\":\"orders\","
                        + "\"username\":\"admin\",\"password\":\"secret\"}")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private Connection mockConnection(String... values) throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        for (int i = 0; i < values.length; i++) {
            when(resultSet.getString(1)).thenReturn(values[0], values[1]);
        }
        return connection;
    }
}

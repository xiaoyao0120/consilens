package com.consilens.server.application.datasource;

import com.consilens.server.api.dto.ConnectionTestRequest;
import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.api.dto.DataSourceCreateRequest;
import com.consilens.server.api.dto.DataSourceDto;
import com.consilens.server.api.dto.DataSourceTypeDto;
import com.consilens.server.api.dto.MetadataColumnDto;
import com.consilens.server.application.connection.ConnectionTestService;
import com.consilens.server.application.connection.JdbcConnectionSupport;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.repository.DataSourceRepository;
import com.consilens.server.support.crypto.CryptoSupport;
import com.consilens.connector.api.DatabaseDialect;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DataSourceServiceImpl implements DataSourceService {

    private final DataSourceRepository dataSourceRepository;
    private final DialectSupport dialectSupport;
    private final ConnectionTestService connectionTestService;
    private final ObjectMapper objectMapper;
    private final MetadataConnectionOpener metadataConnectionOpener;
    private final CryptoSupport cryptoSupport;

    @Autowired
    public DataSourceServiceImpl(DataSourceRepository dataSourceRepository,
                                    DialectSupport dialectSupport,
                                    ConnectionTestService connectionTestService,
                                    ObjectMapper objectMapper,
                                    CryptoSupport cryptoSupport) {
        this(dataSourceRepository, dialectSupport, connectionTestService, objectMapper, cryptoSupport,
                DataSourceServiceImpl::openJdbcConnection);
    }

    DataSourceServiceImpl(DataSourceRepository dataSourceRepository,
                             DialectSupport dialectSupport,
                             ConnectionTestService connectionTestService,
                             ObjectMapper objectMapper,
                             CryptoSupport cryptoSupport,
                             MetadataConnectionOpener metadataConnectionOpener) {
        this.dataSourceRepository = dataSourceRepository;
        this.dialectSupport = dialectSupport;
        this.connectionTestService = connectionTestService;
        this.objectMapper = objectMapper;
        this.cryptoSupport = cryptoSupport;
        this.metadataConnectionOpener = metadataConnectionOpener;
    }

    /**
     * 生产实现：SSRF 校验（IP 字面量建连）后通过 connector 方言建立 JDBC 连接。
     */
    private static Connection openJdbcConnection(DatabaseDialect dialect, Map<String, Object> param) throws Exception {
        InetAddress target = JdbcConnectionSupport.validateTargetHost(stringValue(param.get("host")));
        return JdbcConnectionSupport.open(dialect,
                JdbcConnectionSupport.formatHost(target.getHostAddress()),
                integerValue(param.get("port")),
                stringValue(param.get("database")),
                stringValue(param.get("username")),
                stringValue(param.get("password")),
                JdbcConnectionSupport.DEFAULT_CONNECT_TIMEOUT_SECONDS);
    }

    @Override
    public List<DataSourceTypeDto> listTypes() {
        return dialectSupport.listTypes();
    }

    @Override
    public DataSourceDto create(DataSourceCreateRequest request) {
        dataSourceRepository.findByName(request.getName()).ifPresent(record -> {
            throw new IllegalArgumentException("datasource name already exists: " + request.getName());
        });
        Map<String, Object> param = new java.util.HashMap<>(request.getParam() == null
                ? java.util.Map.of()
                : request.getParam());
        validateParam(param);
        protectPassword(param);
        DataSourceRecord saved = dataSourceRepository.save(DataSourceRecord.builder()
                .name(request.getName())
                .type(request.getType())
                .paramJson(toParamJson(param))
                .build());
        return toDto(saved);
    }

    @Override
    public List<DataSourceDto> list() {
        return dataSourceRepository.listAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public DataSourceDto get(Long id) {
        return toDto(require(id));
    }

    @Override
    public DataSourceDto update(Long id, DataSourceCreateRequest request) {
        DataSourceRecord record = require(id);
        dataSourceRepository.findByName(request.getName())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("datasource name already exists: " + request.getName());
                });
        Map<String, Object> oldParam = fromParamJson(record.getParamJson());
        Map<String, Object> newParam = request.getParam() == null
                ? new java.util.HashMap<>()
                : new java.util.HashMap<>(request.getParam());
        validateParam(newParam);
        // 密码留空（null/空串）时保留旧密码，避免前端编辑时丢失
        Object newPassword = newParam.get("password");
        if (newPassword == null || String.valueOf(newPassword).isBlank()) {
            newParam.put("password", oldParam.get("password"));
        }
        protectPassword(newParam);
        record.setName(request.getName());
        record.setType(request.getType());
        record.setParamJson(toParamJson(newParam));
        return toDto(dataSourceRepository.save(record));
    }

    @Override
    public void delete(Long id) {
        dataSourceRepository.deleteById(id);
    }

    @Override
    public ConnectionTestResponse test(Long id) {
        DataSourceRecord record = require(id);
        Map<String, Object> param = fromParamJson(record.getParamJson());
        return connectionTestService.test(ConnectionTestRequest.builder()
                .type(record.getType())
                .host(stringValue(param.get("host")))
                .port(integerValue(param.get("port")))
                .database(stringValue(param.get("database")))
                .username(stringValue(param.get("username")))
                .password(stringValue(param.get("password")))
                .build());
    }

    // ===== 实时元数据 =====

    @Override
    public List<String> getDatabases(Long id) {
        return withConnection(id, (dialect, connection) ->
                queryStringList(connection, dialect.getMetadataQueryGenerator().getSchemasSQL()));
    }

    @Override
    public List<String> getTables(Long id, String database) {
        return withConnection(id, (dialect, connection) ->
                queryStringList(connection, dialect.getMetadataQueryGenerator().getTablesSQL(database)));
    }

    @Override
    public List<MetadataColumnDto> getColumns(Long id, String database, String table) {
        return withConnection(id, (dialect, connection) ->
                queryColumns(connection, dialect.getMetadataQueryGenerator().getTableColumnsSQL(database, table)));
    }

    private <T> T withConnection(Long id, JdbcQuery<T> query) {
        DataSourceRecord record = require(id);
        Map<String, Object> param = fromParamJson(record.getParamJson());
        DatabaseDialect dialect = dialectSupport.find(record.getType())
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported datasource type: " + record.getType()));
        try (Connection connection = metadataConnectionOpener.open(dialect, param)) {
            return query.apply(dialect, connection);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(JdbcConnectionSupport.safeMessage(exception));
        }
    }

    private List<String> queryStringList(Connection connection, String sql) {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        } catch (Exception exception) {
            throw new IllegalStateException(JdbcConnectionSupport.safeMessage(exception));
        }
        return values;
    }

    private List<MetadataColumnDto> queryColumns(Connection connection, String sql) {
        List<MetadataColumnDto> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                String nullable = resultSet.getString(3);
                columns.add(MetadataColumnDto.builder()
                        .name(resultSet.getString(1))
                        .dataType(resultSet.getString(2))
                        .nullable(nullable == null ? null : !"NO".equalsIgnoreCase(nullable))
                        .build());
            }
        } catch (Exception exception) {
            throw new IllegalStateException(JdbcConnectionSupport.safeMessage(exception));
        }
        return columns;
    }

    private DataSourceRecord require(Long id) {
        return dataSourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("datasource not found: " + id));
    }

    private DataSourceDto toDto(DataSourceRecord record) {
        Map<String, Object> param = fromParamJson(record.getParamJson());
        // 密码不出接口：仅回显 host/port/database/username 等编辑字段
        param.remove("password");
        return DataSourceDto.builder()
                .id(String.valueOf(record.getId()))
                .name(record.getName())
                .type(record.getType())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .param(param)
                .build();
    }

    private String toParamJson(Map<String, Object> param) {
        try {
            return objectMapper.writeValueAsString(param == null ? Map.of() : param);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid datasource param");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromParamJson(String json) {
        try {
            Map<String, Object> param = objectMapper.readValue(json, Map.class);
            Object password = param.get("password");
            if (password != null) {
                // 解密为明文供连接使用；解密失败（换 key/损坏）时置 null，操作可继续
                param.put("password", cryptoSupport.reveal(String.valueOf(password)));
            }
            return param;
        } catch (Exception exception) {
            throw new IllegalStateException("datasource param is corrupted");
        }
    }

    /**
     * 参数校验：host 必填且字符安全、database 字符安全、port 为合法端口。
     * 防止非法值注入 JDBC URL（如 database 携带 ?/; 注入连接属性）。
     */
    private void validateParam(Map<String, Object> param) {
        Object host = param.get("host");
        if (host == null || String.valueOf(host).isBlank()) {
            throw new IllegalArgumentException("param.host is required");
        }
        if (!String.valueOf(host).matches("[A-Za-z0-9._:\\-]{1,128}")) {
            throw new IllegalArgumentException("param.host contains invalid characters");
        }
        Object database = param.get("database");
        if (database != null && !String.valueOf(database).isBlank()
                && !String.valueOf(database).matches("[A-Za-z0-9_.$: \\-]{1,256}")) {
            throw new IllegalArgumentException("param.database contains invalid characters");
        }
        Object port = param.get("port");
        if (port != null) {
            Integer portValue = integerValue(port);
            if (portValue == null || portValue < 1 || portValue > 65535) {
                throw new IllegalArgumentException("param.port is invalid");
            }
        }
    }

    private void protectPassword(Map<String, Object> param) {
        Object password = param.get("password");
        if (password != null && !String.valueOf(password).isBlank()) {
            param.put("password", cryptoSupport.protect(String.valueOf(password)));
        } else {
            param.remove("password");
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @FunctionalInterface
    private interface JdbcQuery<T> {
        T apply(DatabaseDialect dialect, Connection connection);
    }

    @FunctionalInterface
    interface MetadataConnectionOpener {
        Connection open(DatabaseDialect dialect, Map<String, Object> param) throws Exception;
    }
}

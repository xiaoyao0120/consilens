package com.consilens.server.application.datasource;

import com.consilens.server.api.dto.ConnectionTestRequest;
import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.api.dto.DataSourceCreateRequest;
import com.consilens.server.api.dto.DataSourceDto;
import com.consilens.server.api.dto.DataSourceTypeDto;
import com.consilens.server.api.dto.MetadataColumnDto;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.application.connection.ConnectionTestService;
import com.consilens.server.application.connection.JdbcConnectionSupport;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.DataSourcePage;
import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.repository.DataSourceRepository;
import com.consilens.server.support.crypto.CryptoSupport;
import com.consilens.conncetor.base.BaseDataSourceConfigBuilder;
import com.consilens.connector.api.DataSourceConfigBuilder;
import com.consilens.connector.api.DataSourceField;
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
import java.util.LinkedHashMap;
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
                JdbcConnectionSupport.DEFAULT_CONNECT_TIMEOUT_SECONDS,
                extraJdbcParams(param));
    }

    /** 方言扩展参数白名单：仅 sid/schema/properties 进入连接构建（不进 URL 的由 dialect/连接属性处理）。 */
    private static Map<String, Object> extraJdbcParams(Map<String, Object> param) {
        Map<String, Object> extra = new LinkedHashMap<>();
        for (String key : new String[]{"sid", "schema", "properties"}) {
            Object value = param.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                extra.put(key, value);
            }
        }
        return extra;
    }

    @Override
    public List<DataSourceTypeDto> listTypes() {
        return dialectSupport.listTypes();
    }

    @Override
    public List<DataSourceField> getTypeConfig(String type) {
        DatabaseDialect dialect = dialectSupport.find(type)
                .orElseThrow(() -> new IllegalArgumentException("unsupported datasource type: " + type));
        DataSourceConfigBuilder builder = dialect.getDataSourceConfigBuilder();
        if (builder == null) {
            builder = new BaseDataSourceConfigBuilder();
        }
        return builder.build();
    }

    @Override
    public DataSourceDto create(DataSourceCreateRequest request) {
        dataSourceRepository.findByName(request.getName()).ifPresent(record -> {
            throw new IllegalArgumentException("datasource name already exists: " + request.getName());
        });
        Map<String, Object> param = new java.util.HashMap<>(request.getParam() == null
                ? java.util.Map.of()
                : request.getParam());
        stripMaskedSensitiveProperties(param);
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
    public PageResponse<DataSourceDto> listPage(int page, int pageSize) {
        DataSourcePage result = dataSourceRepository.listPage(page, pageSize);
        return PageResponse.<DataSourceDto>builder()
                .total(result.getTotal())
                .page(page)
                .pageSize(pageSize)
                .items(result.getItems().stream().map(this::toDto).collect(Collectors.toList()))
                .build();
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
        stripMaskedSensitiveProperties(newParam);
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
        // 除 username/password 外的键(含 sid/schema/properties 等方言扩展字段)透传给连接测试
        Map<String, Object> options = new LinkedHashMap<>(param);
        options.remove("username");
        options.remove("password");
        return connectionTestService.test(ConnectionTestRequest.builder()
                .type(record.getType())
                .host(stringValue(param.get("host")))
                .port(integerValue(param.get("port")))
                .database(stringValue(param.get("database")))
                .username(stringValue(param.get("username")))
                .password(stringValue(param.get("password")))
                .options(options)
                .build());
    }

    // ===== 实时元数据 =====

    @Override
    public List<String> getDatabases(Long id) {
        return withConnection(id, (dialect, connection, param) ->
                queryStringList(connection, dialect.getMetadataQueryGenerator().getDatabaseListSQL()));
    }

    @Override
    public List<String> getTables(Long id, String database) {
        return withConnection(id, (dialect, connection, param) ->
                queryStringList(connection, dialect.getMetadataQueryGenerator()
                        .getTablesSQL(schemaOf(param, database))));
    }

    @Override
    public List<MetadataColumnDto> getColumns(Long id, String database, String table) {
        return withConnection(id, (dialect, connection, param) ->
                queryColumns(connection, dialect.getMetadataQueryGenerator()
                        .getTableColumnsSQL(schemaOf(param, database), table)));
    }

    private <T> T withConnection(Long id, JdbcQuery<T> query) {
        DataSourceRecord record = require(id);
        Map<String, Object> param = fromParamJson(record.getParamJson());
        DatabaseDialect dialect = dialectSupport.find(record.getType())
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported datasource type: " + record.getType()));
        try (Connection connection = metadataConnectionOpener.open(dialect, param)) {
            return query.apply(dialect, connection, param);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(JdbcConnectionSupport.safeMessage(exception));
        }
    }

    /**
     * 元数据查询的 schema 解析：数据源参数带 schema(如 PG/SQL Server)时优先用它,
     * 否则回退请求路径上的 database 参数,保持 MySQL 等旧行为不变。
     */
    private static String schemaOf(Map<String, Object> param, String fallback) {
        Object schema = param.get("schema");
        return schema != null && !String.valueOf(schema).isBlank() ? String.valueOf(schema) : fallback;
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
        // properties 中可能存在的敏感键（存量数据）同样脱敏，不回显明文
        Object properties = param.get("properties");
        if (properties != null) {
            String masked = maskSensitiveProperties(String.valueOf(properties));
            if (masked.isEmpty()) {
                param.remove("properties");
            } else {
                param.put("properties", masked);
            }
        }
        return DataSourceDto.builder()
                .id(String.valueOf(record.getId()))
                .name(record.getName())
                .type(record.getType())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .param(param)
                .build();
    }

    /** properties 敏感键的值替换为掩码（仅用于回显，不落库）。 */
    private static String maskSensitiveProperties(String properties) {
        StringBuilder builder = new StringBuilder();
        for (String pair : properties.split("&")) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String key = separator > 0 ? pair.substring(0, separator).trim() : pair.trim();
            if (!key.isEmpty() && SENSITIVE_PROPERTY_KEYS.contains(key.toLowerCase())) {
                if (builder.length() > 0) {
                    builder.append('&');
                }
                builder.append(key).append("=******");
            } else {
                if (builder.length() > 0) {
                    builder.append('&');
                }
                builder.append(pair);
            }
        }
        return builder.toString();
    }

    /** 落库前移除 properties 中的掩码敏感键（值为纯 * 视为未修改的占位，不写入）。 */
    private static void stripMaskedSensitiveProperties(Map<String, Object> param) {
        Object properties = param.get("properties");
        if (properties == null) {
            return;
        }
        String text = String.valueOf(properties);
        if (text.isBlank()) {
            return;
        }
        List<String> kept = new java.util.ArrayList<>();
        for (String pair : text.split("&")) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String key = separator > 0 ? pair.substring(0, separator).trim() : pair.trim();
            String value = separator > 0 ? pair.substring(separator + 1).trim() : "";
            if (!key.isEmpty() && SENSITIVE_PROPERTY_KEYS.contains(key.toLowerCase())
                    && value.matches("\\*+")) {
                continue; // 掩码占位：跳过，不写入
            }
            kept.add(pair);
        }
        if (kept.isEmpty()) {
            param.remove("properties");
        } else {
            param.put("properties", String.join("&", kept));
        }
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

    /** 名称类字段(host/database/sid/schema)允许的字符,防止非法值注入 JDBC URL。 */
    private static final String NAME_CHARS_PATTERN = "[A-Za-z0-9_.$: \\-]{1,256}";

    /** 连接参数串(properties)允许的字符,形如 key=value&key2=value2。 */
    private static final String PROPERTIES_PATTERN = "[A-Za-z0-9_=.,&:\\- ]{0,1024}";

    /** properties 中禁止携带的敏感 JDBC 连接键（身份/超时由系统接管）。 */
    private static final java.util.Set<String> SENSITIVE_PROPERTY_KEYS =
            java.util.Set.of("user", "password", "connecttimeout", "logintimeout");

    /**
     * 参数校验：host 必填且字符安全、database/sid/schema 字符安全、properties 字符安全、
     * port 为合法端口。防止非法值注入 JDBC URL（如 database 携带 ?/; 注入连接属性）。
     */
    private void validateParam(Map<String, Object> param) {
        Object host = param.get("host");
        if (host == null || String.valueOf(host).isBlank()) {
            throw new IllegalArgumentException("param.host is required");
        }
        if (!String.valueOf(host).matches("[A-Za-z0-9._:\\-]{1,128}")) {
            throw new IllegalArgumentException("param.host contains invalid characters");
        }
        validateNameField(param, "database");
        validateNameField(param, "sid");
        validateNameField(param, "schema");
        Object port = param.get("port");
        if (port != null) {
            Integer portValue = integerValue(port);
            if (portValue == null || portValue < 1 || portValue > 65535) {
                throw new IllegalArgumentException("param.port is invalid");
            }
        }
        Object properties = param.get("properties");
        if (properties != null && !String.valueOf(properties).isBlank()) {
            String propertiesText = String.valueOf(properties);
            if (!propertiesText.matches(PROPERTIES_PATTERN)) {
                throw new IllegalArgumentException("param.properties contains invalid characters");
            }
            for (String pair : propertiesText.split("&")) {
                int separator = pair.indexOf('=');
                String key = separator > 0 ? pair.substring(0, separator).trim() : pair.trim();
                if (SENSITIVE_PROPERTY_KEYS.contains(key.toLowerCase())) {
                    throw new IllegalArgumentException("param.properties contains sensitive key: " + key);
                }
            }
        }
    }

    /** 可空的名称类字段校验(与 database 同一正则)。 */
    private void validateNameField(Map<String, Object> param, String field) {
        Object value = param.get(field);
        if (value != null && !String.valueOf(value).isBlank()
                && !String.valueOf(value).matches(NAME_CHARS_PATTERN)) {
            throw new IllegalArgumentException("param." + field + " contains invalid characters");
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
        T apply(DatabaseDialect dialect, Connection connection, Map<String, Object> param);
    }

    @FunctionalInterface
    interface MetadataConnectionOpener {
        Connection open(DatabaseDialect dialect, Map<String, Object> param) throws Exception;
    }
}

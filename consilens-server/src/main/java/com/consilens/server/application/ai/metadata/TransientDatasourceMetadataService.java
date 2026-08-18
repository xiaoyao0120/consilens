package com.consilens.server.application.ai.metadata;

import com.consilens.server.api.dto.MetadataColumnDto;

import java.util.List;

/**
 * Reads databases/tables/columns from a transient connection before any
 * datasource is persisted (design section 23.7).
 */
public interface TransientDatasourceMetadataService {

    List<String> listDatabases(TransientConnectionSpec connection);

    List<String> listTables(TransientConnectionSpec connection, String database);

    List<MetadataColumnDto> listColumns(TransientConnectionSpec connection, String database, String table);
}

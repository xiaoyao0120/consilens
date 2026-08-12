package com.consilens.server.domain.repository;

import com.consilens.server.domain.model.DataSourceRecord;

import java.util.List;
import java.util.Optional;

public interface DataSourceRepository {

    DataSourceRecord save(DataSourceRecord record);

    Optional<DataSourceRecord> findById(Long id);

    Optional<DataSourceRecord> findByName(String name);

    List<DataSourceRecord> listAll();

    void deleteById(Long id);
}

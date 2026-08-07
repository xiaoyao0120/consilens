package com.consilens.benchmark.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;

/**
 * 基于 Jackson 的基线 JSON 读写，支持原子写与缺文件返回空基线。
 *
 * 文件结构：{@code {version, createdAt, jdk, entries: {key: BaselineEntry}}}。
 * 提供静态 {@link #load(Path)} 与 {@link #store(Baseline, Path)}，供 runner 调用。
 */
@Slf4j
public class BaselineStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private BaselineStore() {
    }

    /** 读取基线；文件不存在或解析失败时返回空基线（不抛）。 */
    public static Baseline load(Path path) {
        if (!Files.exists(path)) {
            log.debug("baseline file not found, return empty: {}", path);
            return emptyBaseline();
        }
        try {
            return MAPPER.readValue(path.toFile(), Baseline.class);
        } catch (IOException e) {
            log.warn("failed to read baseline, return empty: {}", path, e);
            return emptyBaseline();
        }
    }

    /** 原子写入基线（先写临时文件再 move）。失败抛 RuntimeException。 */
    public static void store(Baseline baseline, Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), baseline);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("baseline saved: {}", path);
        } catch (IOException e) {
            throw new RuntimeException("failed to save baseline: " + path, e);
        }
    }

    public static Baseline emptyBaseline() {
        Baseline b = new Baseline();
        b.setVersion(1);
        b.setEntries(new LinkedHashMap<>());
        return b;
    }
}

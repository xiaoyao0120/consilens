package com.consilens.sink.csv;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvDiffRecordSinkTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldEscapeHeaderAndQuoteCarriageReturnValues() throws Exception {
        Path output = tempDir.resolve("diff.csv");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", output.toString());
        properties.put("columns", List.of(
                Map.of("name", "a,b", "value", "${src.c1}"),
                Map.of("name", "x\"y", "value", "${src.c2}")));

        SinkConfig config = new SinkConfig();
        config.setFormat("csv");
        config.setType("diff-record");
        config.setProperties(new ObjectMapper().writeValueAsString(properties));

        DiffContext context = DiffContext.builder().taskId("csv-escape").build();
        DiffRow row = DiffRow.modified(List.of(1),
                List.of("line1\rline2", "a\"b"),
                List.of("line1\rline2", "a\"b"),
                List.of("c1", "c2"), List.of("c1", "c2"));

        CsvDiffRecordSink sink = new CsvDiffRecordSink();
        try {
            sink.open(config, context);
            sink.onDiffRecords(List.of(row), context);
        } finally {
            sink.close();
        }

        String content = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        // 表头中的逗号/引号必须转义，否则列数会错位
        assertTrue(content.startsWith("\"a,b\",\"x\"\"y\"\n"), content);
        // 仅含 \r 的值也必须整体加引号，不能把一行拆成两行
        assertTrue(content.contains("\"line1\rline2\",\"a\"\"b\""), content);
    }
}

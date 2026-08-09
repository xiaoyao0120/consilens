package com.consilens.server.application.capability;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffResult;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunTaskHandlerTest {

    @Test
    void shouldNotExposeRawDiffValuesByDefault() {
        RunTaskHandler handler = new RunTaskHandler(null, null);
        DiffRow row = DiffRow.modified(List.of(1),
                List.of("sensitive-source"),
                List.of("sensitive-target"),
                List.of("name"),
                List.of("name"));

        Map<String, Object> result = handler.diffRow(row);

        assertThat(result).containsKeys("operation", "primaryKey", "metadata");
        assertThat(result).doesNotContainKeys("sourceValues", "targetValues");
    }

    @Test
    void shouldBoundDiffRowsInResultArtifact() {
        RunTaskHandler handler = new RunTaskHandler(null, null);
        List<DiffRow> rows = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            rows.add(DiffRow.added(List.of(i), List.of("v" + i), List.of("id", "value")));
        }
        DiffResult diffResult = DiffResult.builder()
                .differences(rows)
                .completedAt(java.time.Instant.now())
                .build();

        Map<String, Object> content = handler.runResult(diffResult);

        assertThat(content.get("differenceCount")).isEqualTo(2500L);
        assertThat(content.get("differenceSampleSize")).isEqualTo(1000);
        assertThat(content.get("differenceSampleTruncated")).isEqualTo(Boolean.TRUE);
        assertThat((List<?>) content.get("differences")).hasSize(1000);
    }
}

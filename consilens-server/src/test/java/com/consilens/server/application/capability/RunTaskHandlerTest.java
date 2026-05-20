package com.consilens.server.application.capability;

import com.consilens.core.diff.DiffRow;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RunTaskHandlerTest {

    @Test
    void shouldNotExposeRawDiffValuesByDefault() {
        RunTaskHandler handler = new RunTaskHandler(mock(ArtifactService.class),
                mock(ServerCompareConfigService.class));
        DiffRow row = DiffRow.modified(List.of(1),
                List.of("sensitive-source"),
                List.of("sensitive-target"),
                List.of("name"),
                List.of("name"));

        Map<String, Object> result = handler.diffRow(row);

        assertThat(result).containsKeys("operation", "primaryKey", "metadata");
        assertThat(result).doesNotContainKeys("sourceValues", "targetValues");
    }
}

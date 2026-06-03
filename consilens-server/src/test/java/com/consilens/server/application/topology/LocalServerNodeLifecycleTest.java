package com.consilens.server.application.topology;

import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.repository.ServerNodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LocalServerNodeLifecycleTest {

    @Test
    void shouldScheduleHeartbeatFromSecondsProperty() throws NoSuchMethodException {
        Scheduled scheduled = LocalServerNodeLifecycle.class
                .getMethod("heartbeat")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString())
                .contains("consilens.server.node.heartbeat-interval-seconds")
                .doesNotContain("consilens.server.node.heartbeat-interval-ms");
    }

    @Test
    void shouldUseConfiguredNodeKeyWhenProvided() {
        ServerNodeRepository serverNodeRepository = mock(ServerNodeRepository.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getNode().setNodeKey("node-a");
        LocalServerNodeLifecycle lifecycle = new LocalServerNodeLifecycle(serverNodeRepository,
                properties,
                18080);

        assertThat(lifecycle.currentNodeKey()).isEqualTo("node-a");
    }
}

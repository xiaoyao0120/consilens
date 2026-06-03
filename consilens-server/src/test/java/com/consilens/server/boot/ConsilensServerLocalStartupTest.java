package com.consilens.server.boot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "consilens.server.scheduler.enabled=false")
class ConsilensServerLocalStartupTest {

    @Autowired
    private Environment environment;

    @Test
    void shouldUseLocalProfileDefaultsForStartup() {
        assertThat(environment.getDefaultProfiles()).contains("local");
        assertThat(environment.getProperty("consilens.server.database.allow-embedded")).isEqualTo("true");
        assertThat(environment.getProperty("consilens.server.security.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.sql.init.mode")).isEqualTo("always");
    }
}

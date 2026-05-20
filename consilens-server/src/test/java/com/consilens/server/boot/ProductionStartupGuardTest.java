package com.consilens.server.boot;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class ProductionStartupGuardTest {

    @Test
    void shouldRejectEmbeddedDatasourceByDefault() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        ProductionStartupGuard guard = new ProductionStartupGuard(properties, "jdbc:h2:mem:consilens");

        Assertions.assertThrows(IllegalStateException.class,
                () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void shouldAllowEmbeddedDatasourceWhenExplicitlyEnabled() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getDatabase().setAllowEmbedded(true);
        ProductionStartupGuard guard = new ProductionStartupGuard(properties, "jdbc:h2:mem:consilens");

        guard.run(new DefaultApplicationArguments());
    }
}

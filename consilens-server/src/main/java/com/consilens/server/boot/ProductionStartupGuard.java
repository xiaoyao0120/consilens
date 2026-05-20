package com.consilens.server.boot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ProductionStartupGuard implements ApplicationRunner {

    private final ConsilensServerProperties properties;
    private final String datasourceUrl;

    public ProductionStartupGuard(ConsilensServerProperties properties,
                                  @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.properties = properties;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getDatabase().isAllowEmbedded() && isEmbeddedDatasource(datasourceUrl)) {
            throw new IllegalStateException("Embedded datasource is not allowed for consilens-server. "
                    + "Configure a persistent spring.datasource.url or set consilens.server.database.allow-embedded=true.");
        }
    }

    private boolean isEmbeddedDatasource(String url) {
        if (url == null) {
            return true;
        }
        String normalized = url.trim().toLowerCase();
        return normalized.isEmpty()
                || normalized.startsWith("jdbc:h2:mem:")
                || normalized.startsWith("jdbc:hsqldb:mem:")
                || normalized.startsWith("jdbc:derby:memory:");
    }
}

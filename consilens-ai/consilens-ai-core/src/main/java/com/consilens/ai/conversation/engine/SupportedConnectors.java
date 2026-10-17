package com.consilens.ai.conversation.engine;

import com.consilens.connector.api.DatabaseDialectProvider;

import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/**
 * Single source of truth for connector types supported by the conversation layer.
 *
 * <p>The set is discovered through the {@link DatabaseDialectProvider} SPI instead of
 * being hard-coded, so newly registered connector plugins are accepted automatically.
 */
final class SupportedConnectors {

    private static final Set<String> TYPES = loadTypes();

    private SupportedConnectors() {
    }

    static Set<String> types() {
        return TYPES;
    }

    static boolean supports(String connectorType) {
        if (connectorType == null || connectorType.isBlank()) {
            return false;
        }
        return TYPES.contains(connectorType.trim().toLowerCase(Locale.ROOT));
    }

    static String options() {
        return String.join(", ", TYPES);
    }

    private static Set<String> loadTypes() {
        Set<String> types = new TreeSet<>();
        ServiceLoader<DatabaseDialectProvider> providers = ServiceLoader.load(DatabaseDialectProvider.class);
        Iterator<DatabaseDialectProvider> iterator = providers.iterator();
        while (iterator.hasNext()) {
            try {
                DatabaseDialectProvider provider = iterator.next();
                String connectorType = provider.getConnectorType();
                if (connectorType != null && !connectorType.isBlank()) {
                    types.add(connectorType.trim().toLowerCase(Locale.ROOT));
                }
            } catch (ServiceConfigurationError ignored) {
                // Skip unloadable providers instead of breaking connector validation entirely.
            }
        }
        return Collections.unmodifiableSet(types);
    }
}

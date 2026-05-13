package com.consilens.ai.runtime.model;

import com.consilens.ai.session.model.AiSession;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Map;

/**
 * Context provided to an AI task.
 */
@Value
@Builder(toBuilder = true)
public class AiTaskContext {

    AiSession session;
    String userInput;
    AiConsoleCommand command;
    @Singular("attribute")
    Map<String, Object> attributes;

    public Object attribute(String name) {
        return attributes == null ? null : attributes.get(name);
    }

    public <T> T attribute(String name, Class<T> type) {
        Object value = attribute(name);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("Attribute " + name + " is not a " + type.getSimpleName());
        }
        return type.cast(value);
    }
}

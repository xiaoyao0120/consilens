package com.consilens.ai.runtime.model;

import lombok.Builder;
import lombok.Value;

/**
 * Parsed REPL command with optional argument payload.
 */
@Value
@Builder
public class AiConsoleCommand {

    String name;
    String argument;
}

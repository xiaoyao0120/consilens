package com.consilens.server.api.dto.ai;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;

/**
 * Out-of-band secret submission. The values map is never logged, stored in
 * events or placed into the model context; toString must not print values.
 */
@Getter
@Setter
public class SubmitSecretRequest {
    private String secretRequestId;
    @ToString.Exclude
    private Map<String, String> values;
}

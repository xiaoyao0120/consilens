package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskQueryResponse {

    private String taskId;
    private String taskType;
    private String status;
    private String traceId;
    private String executeNodeKey;
    private List<ArtifactRefDto> artifacts;
    private List<String> availableNextActions;
}

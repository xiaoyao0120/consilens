package com.consilens.server.application.capability.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComparisonConfig {

    @Builder.Default
    private List<String> fields = new ArrayList<>();

    @Builder.Default
    private List<String> ignoreColumns = new ArrayList<>();
}

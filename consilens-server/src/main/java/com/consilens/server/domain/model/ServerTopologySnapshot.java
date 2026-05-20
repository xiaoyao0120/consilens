package com.consilens.server.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerTopologySnapshot {

    private List<ServerNodeRecord> liveNodes;
    private List<ServerNodeRecord> schedulableNodes;
    private int currentSlot;
    private int totalSlot;
    private boolean dispatchable;
    private Instant refreshedAt;
}

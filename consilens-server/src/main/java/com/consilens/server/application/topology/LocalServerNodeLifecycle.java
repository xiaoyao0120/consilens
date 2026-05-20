package com.consilens.server.application.topology;

import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enumtype.ServerNodeStatus;
import com.consilens.server.domain.model.ServerNodeRecord;
import com.consilens.server.domain.repository.ServerNodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;

@Component
public class LocalServerNodeLifecycle {

    private final ServerNodeRepository serverNodeRepository;
    private final ConsilensServerProperties properties;
    private final int port;

    public LocalServerNodeLifecycle(ServerNodeRepository serverNodeRepository,
                                    ConsilensServerProperties properties,
                                    @Value("${server.port}") int port) {
        this.serverNodeRepository = serverNodeRepository;
        this.properties = properties;
        this.port = port;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        heartbeat();
    }

    @Scheduled(fixedDelayString = "${consilens.server.node.heartbeat-interval-ms:2000}")
    public void heartbeat() {
        Instant now = Instant.now();
        String host = resolveHost();
        String nodeKey = host + ":" + port;
        Optional<ServerNodeRecord> existing = serverNodeRepository.findByNodeKey(nodeKey);
        ServerNodeRecord record = existing.orElseGet(ServerNodeRecord::new);
        record.setNodeKey(nodeKey);
        record.setHost(host);
        record.setPort(port);
        record.setMachineCode(resolveMachineCode());
        record.setStatus(ServerNodeStatus.ONLINE);
        record.setLoadAverage(currentLoadAverage());
        record.setAvailableMemoryMb(currentAvailableMemoryMb());
        record.setHeartbeatTime(now);
        record.setStatusUpdateTime(now);
        record.setStatusUpdateBy(nodeKey);
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(now);
        }
        record.setUpdatedAt(now);
        serverNodeRepository.save(record);
    }

    public String currentNodeKey() {
        return resolveHost() + ":" + port;
    }

    private String resolveHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception exception) {
            return "127.0.0.1";
        }
    }

    private String resolveMachineCode() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception exception) {
            return "unknown";
        }
    }

    private double currentLoadAverage() {
        return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    }

    private double currentAvailableMemoryMb() {
        long bytes = Runtime.getRuntime().freeMemory();
        return bytes / 1024d / 1024d;
    }
}

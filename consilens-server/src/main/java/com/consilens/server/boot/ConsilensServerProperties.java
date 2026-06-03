package com.consilens.server.boot;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "consilens.server")
public class ConsilensServerProperties {

    private final Node node = new Node();
    private final Scheduler scheduler = new Scheduler();
    private final Artifact artifact = new Artifact();
    private final Database database = new Database();
    private final Security security = new Security();
    private final Api api = new Api();

    @Data
    public static class Node {
        private String nodeKey;
        private int heartbeatIntervalSeconds = 2;
        private int expireSeconds = 30;
        private int topologyRefreshSeconds = 3;
    }

    @Data
    public static class Scheduler {
        private boolean enabled = true;
        private int commandPollIntervalMs = 1000;
        private int claimLeaseSeconds = 15;
        private int executeThreads = 2;
        private int executeQueueCapacity = 1024;
        private int recoveryBatchSize = 64;
        private int maxRetryCount = 3;
    }

    @Data
    public static class Artifact {
        private String storageType = "local-file";
        private String localBaseDir = "./.consilens-server/artifacts";
        private long maxContentBytes = 50L * 1024L * 1024L;
    }

    @Data
    public static class Database {
        private boolean allowEmbedded = false;
    }

    @Data
    public static class Security {
        private boolean enabled = true;
        private String apiKey;
    }

    @Data
    public static class Api {
        private long maxRequestBodyBytes = 10L * 1024L * 1024L;
    }
}

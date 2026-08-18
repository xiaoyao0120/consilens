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
    private final Ai ai = new Ai();

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
        private String timeZone = "";
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

    /**
     * AI Agent runtime settings (design section 30). API key and secret key
     * are referenced by environment variable name only; values never appear
     * in properties or actuator output.
     */
    @Data
    public static class Ai {
        private boolean enabled = false;
        private String backend = "deepseek";
        private String model = "deepseek-chat";
        private String baseUrl = "https://api.deepseek.com";
        private String apiKeyEnv = "DEEPSEEK_API_KEY";
        /** 直接配置的 API Key 值（优先于 apiKeyEnv 环境变量；仅本地开发建议）。 */
        private String apiKey = "";
        private int maxTurns = 8;
        private int maxToolCalls = 20;
        private int runTimeoutSeconds = 120;
        private int toolTimeoutSeconds = 30;
        private int leaseSeconds = 30;
        private int secretTtlSeconds = 600;
        private int secretMaxReads = 8;
        private String secretStore = "encrypted-db";
        private String secretKeyEnv = "CONSILENS_AGENT_SECRET_KEY";
        /** 直接配置的临时 secret 加密 key（Base64 32 字节，优先于 secretKeyEnv）。 */
        private String secretKey = "";
        private int maxConcurrentRuns = 4;
        private boolean pollerEnabled = true;
        private boolean failFast = true;
    }
}

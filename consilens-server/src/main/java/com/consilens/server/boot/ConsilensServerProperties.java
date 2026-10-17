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
        private int leaseSeconds = 30;
        private int secretTtlSeconds = 600;
        private int secretMaxReads = 8;
        private String secretStore = "encrypted-db";
        private String secretKeyEnv = "CONSILENS_AGENT_SECRET_KEY";
        /** 直接配置的临时 secret 加密 key（Base64 32 字节，优先于 secretKeyEnv）。 */
        private String secretKey = "";
        private int maxConcurrentRuns = 4;
        private boolean failFast = true;
        /** 模型客户端连接超时（秒）。 */
        private int connectTimeoutSeconds = 10;
        /** 模型调用可重试次数（不含首次请求）。 */
        private int maxRetries = 2;
        /** 过期 secret 清理任务的执行间隔（毫秒）。 */
        private long secretCleanupIntervalMs = 600_000L;
        /** inspect_draft_metadata 单次返回的列数上限，超出时置 truncated。 */
        private int maxInspectedColumns = 200;
        /** run 结果 artifact 内联的差异样本行数上限。 */
        private int diffSampleRows = 1000;

        private final Sse sse = new Sse();

        /** Agent 事件 SSE 流的并发与轮询设置。 */
        @Data
        public static class Sse {
            /** 同时保持的 SSE 连接上限；超出时新连接直接返回 503。 */
            private int maxConnections = 64;
            /** 心跳注释（heartbeat）发送间隔（毫秒）。 */
            private long heartbeatMs = 15_000L;
            /** 轮询新事件的基础间隔（毫秒）。 */
            private long pollMs = 1_000L;
        }
    }
}

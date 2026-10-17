package com.consilens.server.application.ai;

/**
 * 审批 {@code proposedRunId} 与 run {@code resumeFromRunId} 之间的字符串协议前缀。
 *
 * <p>前缀只是队列内的恢复标记，不是外部 API 的一部分；集中定义避免生产端
 * （审批/入队）与消费端（worker）各写一份字面量后悄悄失配。
 */
public final class AgentResumeKeys {

    /** 恢复一次任务运行：后缀是 approvalId（历史格式里也可能是 definitionId）。 */
    public static final String RUN_PREFIX = "run:";

    /** 恢复一次计划执行：后缀是 planId。 */
    public static final String PLAN_PREFIX = "plan:";

    private AgentResumeKeys() {
    }
}

package com.consilens.server.application.ai.plan;

import com.consilens.agent.api.plan.AgentPlanActionStatus;
import com.consilens.agent.api.store.AgentResourceRef;
import com.consilens.server.infrastructure.db.mapper.ai.AiPlanActionMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiPlanMapper;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Executes one plan action atomically: the business resource write and the
 * cs_ai_plan_action SUCCEEDED marker (plus the plan version bump) commit in
 * the same short database transaction, so a crash never leaves "resource
 * created but action unknown" or the reverse.
 */
public class ProvisioningActionTransactionService {

    private final CreateDatasourceCommandHandler datasourceHandler;
    private final CreateTaskDefinitionCommandHandler taskHandler;
    private final AiPlanActionMapper actionMapper;
    private final AiPlanMapper planMapper;

    public ProvisioningActionTransactionService(CreateDatasourceCommandHandler datasourceHandler,
                                                CreateTaskDefinitionCommandHandler taskHandler,
                                                AiPlanActionMapper actionMapper,
                                                AiPlanMapper planMapper) {
        this.datasourceHandler = datasourceHandler;
        this.taskHandler = taskHandler;
        this.actionMapper = actionMapper;
        this.planMapper = planMapper;
    }

    @Transactional
    public AgentResourceRef completeDatasourceAction(String planId, String actionId,
                                                     String sessionId, String actorId, String draftId,
                                                     long expectedPlanVersion) {
        AgentResourceRef ref = datasourceHandler.create(sessionId, actorId, draftId);
        markSucceeded(planId, actionId, ref, expectedPlanVersion);
        return ref;
    }

    @Transactional
    public AgentResourceRef completeTaskAction(String planId, String actionId,
                                               Map<String, Object> safeArgs,
                                               String sourceDatasourceId, String targetDatasourceId,
                                               String sourceType, String targetType,
                                               long expectedPlanVersion) {
        AgentResourceRef ref = taskHandler.create(safeArgs, sourceDatasourceId, targetDatasourceId,
                sourceType, targetType);
        markSucceeded(planId, actionId, ref, expectedPlanVersion);
        return ref;
    }

    /**
     * REUSE_DATASOURCE 不写业务资源，但同样要推进计划版本并标记 action
     * SUCCEEDED，保证后续动作的乐观锁版本连续（否则任务动作必然版本冲突）。
     */
    @Transactional
    public void completeReuseAction(String planId, String actionId, AgentResourceRef ref,
                                    long expectedPlanVersion) {
        markSucceeded(planId, actionId, ref, expectedPlanVersion);
    }

    private void markSucceeded(String planId, String actionId, AgentResourceRef ref,
                               long expectedPlanVersion) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        actionMapper.complete(planId, actionId, AgentPlanActionStatus.SUCCEEDED.name(),
                ref.getResourceType().name(), ref.getResourceId(), null, false, now);
        int bumped = planMapper.bumpVersion(planId, expectedPlanVersion);
        if (bumped != 1) {
            throw new IllegalStateException("plan version conflict while completing action " + actionId);
        }
    }
}

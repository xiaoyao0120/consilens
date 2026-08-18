package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.event.AgentEventVisibility;
import com.consilens.agent.api.plan.AgentPlanAction;
import com.consilens.agent.api.plan.AgentPlanActionStatus;
import com.consilens.agent.api.plan.AgentPlanActionType;
import com.consilens.agent.api.plan.AgentPlanStatus;
import com.consilens.agent.api.plan.AgentProvisioningPlan;
import com.consilens.agent.api.state.AgentRunStatus;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.store.AgentApprovalDecision;
import com.consilens.agent.api.store.AgentApprovalRecord;
import com.consilens.agent.api.store.AgentApprovalStatus;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.api.store.AgentResourceRef;
import com.consilens.agent.api.store.AgentResourceType;
import com.consilens.agent.api.store.AgentRunRecord;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.api.store.AgentSnapshotRecord;
import com.consilens.agent.api.store.AgentToolCallRecord;
import com.consilens.agent.api.tool.AgentToolCallStatus;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.server.infrastructure.db.entity.ai.AiApprovalEntity;
import com.consilens.server.infrastructure.db.entity.ai.AiEventEntity;
import com.consilens.server.infrastructure.db.entity.ai.AiPlanActionEntity;
import com.consilens.server.infrastructure.db.entity.ai.AiPlanEntity;
import com.consilens.server.infrastructure.db.entity.ai.AiRunEntity;
import com.consilens.server.infrastructure.db.entity.ai.AiSessionEntity;
import com.consilens.server.infrastructure.db.entity.ai.AiSnapshotEntity;
import com.consilens.server.infrastructure.db.entity.ai.AiToolCallEntity;
import com.consilens.server.infrastructure.db.mapper.ai.AiApprovalMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiEventMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiPlanActionMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiPlanMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiRunMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiSecretMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiSessionMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiSnapshotMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiToolCallMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MyBatis-backed AgentPersistence (design section 26). Every mutation is
 * expressed as a database CAS; methods return false on conflicts, never throw.
 * Multi-statement atomic operations run in a short transaction.
 */
public class MyBatisAgentPersistence implements AgentPersistence {

    private final SqlSessionFactory sessionFactory;
    private final ObjectMapper mapper;

    public MyBatisAgentPersistence(SqlSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    // ---------------------------------------------------------------- session

    @Override
    public Optional<AgentSessionRecord> findSession(String sessionId) {
        try (SqlSession session = sessionFactory.openSession()) {
            AiSessionEntity entity = session.getMapper(AiSessionMapper.class).findById(sessionId);
            return Optional.ofNullable(entity).map(this::toSessionRecord);
        }
    }

    @Override
    public AgentSessionRecord createSession(AgentSessionRecord record) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            AiSessionMapper mapper = session.getMapper(AiSessionMapper.class);
            AiSessionEntity existing = mapper.findById(record.getId());
            if (existing != null) {
                return toSessionRecord(existing);
            }
            AiSessionEntity byRequest = mapper.findByActorAndRequestId(record.getActorId(), record.getRequestId());
            if (byRequest != null) {
                return toSessionRecord(byRequest);
            }
            Instant now = Instant.now();
            AiSessionEntity entity = new AiSessionEntity();
            entity.setId(record.getId());
            entity.setActorId(record.getActorId());
            entity.setRequestId(record.getRequestId());
            entity.setTitle(record.getTitle());
            entity.setObjective(record.getObjective());
            entity.setStatus(record.getStatus() == null ? AgentSessionStatus.READY.name() : record.getStatus().name());
            entity.setWorkflowStage(record.getWorkflowStage() == null
                    ? AgentWorkflowStage.DISCOVERY.name() : record.getWorkflowStage().name());
            entity.setActiveRunId(record.getActiveRunId());
            entity.setNextSeq(record.getNextSeq());
            entity.setSnapshotSeq(record.getSnapshotSeq());
            entity.setVersion(1L);
            entity.setCreatedAt(toLocal(now));
            entity.setUpdatedAt(toLocal(now));
            mapper.insert(entity);
            return toSessionRecord(entity);
        }
    }

    @Override
    public boolean updateSession(AgentSessionRecord record) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            AiSessionMapper mapper = session.getMapper(AiSessionMapper.class);
            AiSessionEntity entity = toSessionEntity(record);
            entity.setVersion(record.getVersion() + 1);
            entity.setUpdatedAt(toLocal(Instant.now()));
            return mapper.updateWithVersion(entity, record.getVersion()) == 1;
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        try (SqlSession session = sessionFactory.openSession(false)) {
            session.getMapper(AiPlanActionMapper.class).deleteBySessionId(sessionId);
            session.getMapper(AiPlanMapper.class).deleteBySessionId(sessionId);
            session.getMapper(AiToolCallMapper.class).deleteBySessionId(sessionId);
            session.getMapper(AiRunMapper.class).deleteBySessionId(sessionId);
            session.getMapper(AiEventMapper.class).deleteBySessionId(sessionId);
            session.getMapper(AiSnapshotMapper.class).deleteBySessionId(sessionId);
            session.getMapper(AiApprovalMapper.class).deleteBySessionId(sessionId);
            session.getMapper(AiSecretMapper.class).deleteBySessionId(sessionId);
            session.getMapper(AiSessionMapper.class).deleteById(sessionId);
            session.commit();
        }
    }

    // ------------------------------------------------------------------ lease

    @Override
    public boolean tryAcquireLease(String sessionId, String actorId, String workerId,
                                   Instant now, Instant expiresAt) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            return session.getMapper(AiSessionMapper.class)
                    .tryAcquireLease(sessionId, actorId, workerId, toLocal(now), toLocal(expiresAt)) == 1;
        }
    }

    @Override
    public boolean renewLease(String sessionId, String workerId, long expectedVersion, Instant expiresAt) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            return session.getMapper(AiSessionMapper.class)
                    .renewLease(sessionId, workerId, expectedVersion, toLocal(Instant.now()),
                            toLocal(expiresAt)) == 1;
        }
    }

    @Override
    public boolean releaseLease(String sessionId, String workerId) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            return session.getMapper(AiSessionMapper.class)
                    .releaseLease(sessionId, workerId, toLocal(Instant.now())) == 1;
        }
    }

    // ----------------------------------------------------------------- events

    @Override
    public List<AgentEvent> appendEvents(String sessionId, long expectedNextSeq, List<AgentEvent> events) {
        try (SqlSession session = sessionFactory.openSession(false)) {
            AiSessionMapper sessionMapper = session.getMapper(AiSessionMapper.class);
            AiEventMapper eventMapper = session.getMapper(AiEventMapper.class);
            int reserved = sessionMapper.advanceNextSeq(sessionId, expectedNextSeq, events.size(),
                    toLocal(Instant.now()));
            if (reserved != 1) {
                session.rollback();
                return null;
            }
            List<AgentEvent> persisted = new ArrayList<>(events.size());
            long seq = expectedNextSeq;
            for (AgentEvent event : events) {
                AgentEvent assigned = event.withSeq(seq++);
                eventMapper.insert(toEventEntity(assigned));
                persisted.add(assigned);
            }
            session.commit();
            return persisted;
        }
    }

    @Override
    public List<AgentEvent> listEventsAfter(String sessionId, long afterSeq, int limit) {
        try (SqlSession session = sessionFactory.openSession()) {
            return session.getMapper(AiEventMapper.class).listAfter(sessionId, afterSeq, limit)
                    .stream().map(this::toAgentEvent).collect(java.util.stream.Collectors.toList());
        }
    }

    @Override
    public long nextSeq(String sessionId) {
        try (SqlSession session = sessionFactory.openSession()) {
            AiSessionEntity entity = session.getMapper(AiSessionMapper.class).findById(sessionId);
            return entity == null || entity.getNextSeq() == null ? 0 : entity.getNextSeq();
        }
    }

    // -------------------------------------------------------------------- run

    @Override
    public Optional<AgentRunRecord> findRunByRequestId(String sessionId, String requestId) {
        try (SqlSession session = sessionFactory.openSession()) {
            AiRunEntity entity = session.getMapper(AiRunMapper.class)
                    .findByRequestId(sessionId, requestId);
            return Optional.ofNullable(entity).map(this::toRunRecord);
        }
    }

    @Override
    public Optional<AgentRunRecord> findRun(String runId) {
        try (SqlSession session = sessionFactory.openSession()) {
            AiRunEntity entity = session.getMapper(AiRunMapper.class).findById(runId);
            return Optional.ofNullable(entity).map(this::toRunRecord);
        }
    }

    @Override
    public AgentRunRecord createRun(AgentRunRecord record) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            AiRunMapper mapper = session.getMapper(AiRunMapper.class);
            if (mapper.findById(record.getRunId()) != null) {
                throw new IllegalStateException("run already exists: " + record.getRunId());
            }
            AiRunEntity entity = toRunEntity(record);
            entity.setVersion(1L);
            mapper.insert(entity);
            return toRunRecord(entity);
        }
    }

    @Override
    public boolean updateRun(AgentRunRecord record) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            AiRunEntity entity = toRunEntity(record);
            entity.setVersion(record.getVersion() + 1);
            return session.getMapper(AiRunMapper.class).updateWithVersion(entity, record.getVersion()) == 1;
        }
    }

    // --------------------------------------------------------------- tool call

    @Override
    public Optional<AgentToolCallRecord> findSucceededByIdempotencyKey(String sessionId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        try (SqlSession session = sessionFactory.openSession()) {
            AiToolCallEntity entity = session.getMapper(AiToolCallMapper.class)
                    .findSucceededByIdempotencyKey(sessionId, idempotencyKey);
            return Optional.ofNullable(entity).map(this::toToolCallRecord);
        }
    }

    @Override
    public Optional<AgentToolCallRecord> findToolCall(String callId) {
        try (SqlSession session = sessionFactory.openSession()) {
            AiToolCallEntity entity = session.getMapper(AiToolCallMapper.class).findById(callId);
            return Optional.ofNullable(entity).map(this::toToolCallRecord);
        }
    }

    @Override
    public AgentToolCallRecord saveToolCall(AgentToolCallRecord record) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            AiToolCallMapper mapper = session.getMapper(AiToolCallMapper.class);
            if (mapper.findById(record.getCallId()) != null) {
                throw new IllegalStateException("tool call already exists: " + record.getCallId());
            }
            AiToolCallEntity entity = toToolCallEntity(record);
            entity.setVersion(1L);
            mapper.insert(entity);
            return toToolCallRecord(entity);
        }
    }

    @Override
    public boolean updateToolCall(AgentToolCallRecord record) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            AiToolCallEntity entity = toToolCallEntity(record);
            entity.setVersion(record.getVersion() + 1);
            return session.getMapper(AiToolCallMapper.class).updateWithVersion(entity, record.getVersion()) == 1;
        }
    }

    // --------------------------------------------------------------- approval

    @Override
    public AgentApprovalRecord createApproval(AgentApprovalRecord record) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            AiApprovalMapper mapper = session.getMapper(AiApprovalMapper.class);
            if (mapper.findById(record.getApprovalId()) != null) {
                throw new IllegalStateException("approval already exists: " + record.getApprovalId());
            }
            AiApprovalEntity entity = toApprovalEntity(record);
            entity.setVersion(1L);
            mapper.insert(entity);
            return toApprovalRecord(entity);
        }
    }

    @Override
    public Optional<AgentApprovalRecord> findApproval(String approvalId) {
        try (SqlSession session = sessionFactory.openSession()) {
            AiApprovalEntity entity = session.getMapper(AiApprovalMapper.class).findById(approvalId);
            return Optional.ofNullable(entity).map(this::toApprovalRecord);
        }
    }

    @Override
    public boolean decideApproval(String approvalId, String actorId, long expectedVersion,
                                  AgentApprovalDecision decision, Instant now) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            String status = decision == AgentApprovalDecision.APPROVE
                    ? AgentApprovalStatus.APPROVED.name() : AgentApprovalStatus.DENIED.name();
            return session.getMapper(AiApprovalMapper.class)
                    .decide(approvalId, actorId, expectedVersion, status, toLocal(now)) == 1;
        }
    }

    // ------------------------------------------------------------------- plan

    @Override
    public boolean saveReadyPlan(String sessionId, AgentProvisioningPlan plan, long expectedSessionVersion) {
        try (SqlSession session = sessionFactory.openSession(false)) {
            AiPlanMapper planMapper = session.getMapper(AiPlanMapper.class);
            AiPlanActionMapper actionMapper = session.getMapper(AiPlanActionMapper.class);
            if (planMapper.findById(plan.getPlanId()) != null) {
                session.rollback();
                return false;
            }
            planMapper.insert(toPlanEntity(plan));
            for (AgentPlanAction action : plan.getActions()) {
                actionMapper.insert(toPlanActionEntity(plan.getPlanId(), action));
            }
            AiSessionEntity current = session.getMapper(AiSessionMapper.class).findById(sessionId);
            if (current == null || current.getVersion() != expectedSessionVersion) {
                session.rollback();
                return false;
            }
            AiSessionEntity bumped = current;
            bumped.setVersion(current.getVersion() + 1);
            bumped.setUpdatedAt(toLocal(Instant.now()));
            if (session.getMapper(AiSessionMapper.class)
                    .updateWithVersion(bumped, expectedSessionVersion) != 1) {
                session.rollback();
                return false;
            }
            session.commit();
            return true;
        }
    }

    @Override
    public Optional<AgentProvisioningPlan> findPlan(String planId) {
        try (SqlSession session = sessionFactory.openSession()) {
            AiPlanEntity entity = session.getMapper(AiPlanMapper.class).findById(planId);
            if (entity == null) {
                return Optional.empty();
            }
            List<AgentPlanAction> actions = session.getMapper(AiPlanActionMapper.class)
                    .listByPlanId(planId).stream().map(this::toPlanAction)
                    .collect(java.util.stream.Collectors.toList());
            return Optional.of(toProvisioningPlan(entity, actions));
        }
    }

    @Override
    public boolean claimApprovedPlan(String planId, long expectedVersion, String workerId) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            return session.getMapper(AiPlanMapper.class)
                    .claimWithVersion(planId, expectedVersion, AgentPlanStatus.EXECUTING.name()) == 1;
        }
    }

    @Override
    public boolean approvePlan(String planId, long expectedVersion) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            return session.getMapper(AiPlanMapper.class).approve(planId, expectedVersion) == 1;
        }
    }

    @Override
    public boolean completePlanAction(String planId, String actionId, AgentResourceRef resourceRef,
                                      long expectedVersion) {
        try (SqlSession session = sessionFactory.openSession(false)) {
            AiPlanActionMapper actionMapper = session.getMapper(AiPlanActionMapper.class);
            int actionRows = actionMapper.complete(planId, actionId, AgentPlanActionStatus.SUCCEEDED.name(),
                    resourceRef == null ? null : resourceRef.getResourceType().name(),
                    resourceRef == null ? null : resourceRef.getResourceId(),
                    null, false, toLocal(Instant.now()));
            int planRows = session.getMapper(AiPlanMapper.class).bumpVersion(planId, expectedVersion);
            if (actionRows != 1 || planRows != 1) {
                session.rollback();
                return false;
            }
            session.commit();
            return true;
        }
    }

    // --------------------------------------------------------------- snapshot

    @Override
    public boolean saveSnapshot(String sessionId, long seq, AgentWorkingState workingState,
                                String conversationSummary, int schemaVersion, long expectedSessionVersion) {
        try (SqlSession session = sessionFactory.openSession(false)) {
            AiSnapshotEntity entity = new AiSnapshotEntity();
            entity.setSessionId(sessionId);
            entity.setSeq(seq);
            entity.setWorkingState(writeJson(workingState));
            entity.setConversationSummary(conversationSummary);
            entity.setSchemaVersion(schemaVersion);
            entity.setCreatedAt(toLocal(Instant.now()));
            session.getMapper(AiSnapshotMapper.class).insert(entity);
            int rows = session.getMapper(AiSessionMapper.class)
                    .advanceSnapshotSeq(sessionId, seq, expectedSessionVersion, toLocal(Instant.now()));
            if (rows != 1) {
                session.rollback();
                return false;
            }
            session.commit();
            return true;
        }
    }

    @Override
    public Optional<AgentSnapshotRecord> latestSnapshot(String sessionId) {
        try (SqlSession session = sessionFactory.openSession()) {
            AiSnapshotEntity entity = session.getMapper(AiSnapshotMapper.class).latest(sessionId);
            if (entity == null) {
                return Optional.empty();
            }
            return Optional.of(AgentSnapshotRecord.builder()
                    .sessionId(entity.getSessionId())
                    .seq(entity.getSeq())
                    .workingState(readJson(entity.getWorkingState(), AgentWorkingState.class))
                    .conversationSummary(entity.getConversationSummary())
                    .schemaVersion(entity.getSchemaVersion())
                    .createdAt(toInstant(entity.getCreatedAt()))
                    .build());
        }
    }

    // ------------------------------------------------------------ conversions

    private AgentSessionRecord toSessionRecord(AiSessionEntity entity) {
        return AgentSessionRecord.builder()
                .id(entity.getId())
                .actorId(entity.getActorId())
                .requestId(entity.getRequestId())
                .title(entity.getTitle())
                .objective(entity.getObjective())
                .status(enumValue(entity.getStatus(), AgentSessionStatus.class, AgentSessionStatus.READY))
                .workflowStage(enumValue(entity.getWorkflowStage(), AgentWorkflowStage.class,
                        AgentWorkflowStage.DISCOVERY))
                .activeRunId(entity.getActiveRunId())
                .nextSeq(entity.getNextSeq() == null ? 0 : entity.getNextSeq())
                .snapshotSeq(entity.getSnapshotSeq() == null ? 0 : entity.getSnapshotSeq())
                .version(entity.getVersion() == null ? 0 : entity.getVersion())
                .leaseOwner(entity.getLeaseOwner())
                .leaseExpiresAt(toInstant(entity.getLeaseExpiresAt()))
                .createdAt(toInstant(entity.getCreatedAt()))
                .updatedAt(toInstant(entity.getUpdatedAt()))
                .build();
    }

    private AiSessionEntity toSessionEntity(AgentSessionRecord record) {
        AiSessionEntity entity = new AiSessionEntity();
        entity.setId(record.getId());
        entity.setActorId(record.getActorId());
        entity.setRequestId(record.getRequestId());
        entity.setTitle(record.getTitle());
        entity.setObjective(record.getObjective());
        entity.setStatus(record.getStatus().name());
        entity.setWorkflowStage(record.getWorkflowStage().name());
        entity.setActiveRunId(record.getActiveRunId());
        entity.setNextSeq(record.getNextSeq());
        entity.setSnapshotSeq(record.getSnapshotSeq());
        entity.setVersion(record.getVersion());
        entity.setLeaseOwner(record.getLeaseOwner());
        entity.setLeaseExpiresAt(toLocal(record.getLeaseExpiresAt()));
        entity.setCreatedAt(toLocal(record.getCreatedAt()));
        entity.setUpdatedAt(toLocal(record.getUpdatedAt()));
        return entity;
    }

    private AiEventEntity toEventEntity(AgentEvent event) {
        AiEventEntity entity = new AiEventEntity();
        entity.setEventId(event.getEventId());
        entity.setSessionId(event.getSessionId());
        entity.setSeq(event.getSeq());
        entity.setRunId(event.getRunId());
        entity.setTurnId(event.getTurnId());
        entity.setEventType(event.getType().name());
        entity.setVisibility(event.getVisibility().name());
        entity.setSchemaVersion(event.getSchemaVersion());
        entity.setPayload(event.getPayload() == null ? null : event.getPayload().toString());
        entity.setCreatedAt(toLocal(event.getCreatedAt()));
        return entity;
    }

    private AgentEvent toAgentEvent(AiEventEntity entity) {
        return AgentEvent.builder()
                .eventId(entity.getEventId())
                .sessionId(entity.getSessionId())
                .seq(entity.getSeq())
                .runId(entity.getRunId())
                .turnId(entity.getTurnId())
                .type(enumValue(entity.getEventType(), AgentEventType.class, AgentEventType.RUN_STARTED))
                .visibility(enumValue(entity.getVisibility(), AgentEventVisibility.class,
                        AgentEventVisibility.AUDIT_ONLY))
                .schemaVersion(entity.getSchemaVersion() == null ? 1 : entity.getSchemaVersion())
                .createdAt(toInstant(entity.getCreatedAt()))
                .payload(readJsonOrEmpty(entity.getPayload()))
                .build();
    }

    private AiRunEntity toRunEntity(AgentRunRecord record) {
        AiRunEntity entity = new AiRunEntity();
        entity.setRunId(record.getRunId());
        entity.setSessionId(record.getSessionId());
        entity.setRequestId(record.getRequestId());
        entity.setResumeFromRunId(record.getResumeFromRunId());
        entity.setStatus(record.getStatus().name());
        entity.setLeaseOwner(record.getLeaseOwner());
        entity.setLeaseExpiresAt(toLocal(record.getLeaseExpiresAt()));
        entity.setTurnCount(record.getTurnCount());
        entity.setToolCallCount(record.getToolCallCount());
        entity.setTokenCount(record.getTokenCount());
        entity.setErrorCode(record.getErrorCode());
        entity.setStartedAt(toLocal(record.getStartedAt()));
        entity.setEndedAt(toLocal(record.getEndedAt()));
        entity.setVersion(record.getVersion());
        return entity;
    }

    private AgentRunRecord toRunRecord(AiRunEntity entity) {
        return AgentRunRecord.builder()
                .runId(entity.getRunId())
                .sessionId(entity.getSessionId())
                .requestId(entity.getRequestId())
                .resumeFromRunId(entity.getResumeFromRunId())
                .status(enumValue(entity.getStatus(), AgentRunStatus.class, AgentRunStatus.QUEUED))
                .leaseOwner(entity.getLeaseOwner())
                .leaseExpiresAt(toInstant(entity.getLeaseExpiresAt()))
                .turnCount(entity.getTurnCount() == null ? 0 : entity.getTurnCount())
                .toolCallCount(entity.getToolCallCount() == null ? 0 : entity.getToolCallCount())
                .tokenCount(entity.getTokenCount() == null ? 0 : entity.getTokenCount())
                .errorCode(entity.getErrorCode())
                .startedAt(toInstant(entity.getStartedAt()))
                .endedAt(toInstant(entity.getEndedAt()))
                .version(entity.getVersion() == null ? 0 : entity.getVersion())
                .build();
    }

    private AiToolCallEntity toToolCallEntity(AgentToolCallRecord record) {
        AiToolCallEntity entity = new AiToolCallEntity();
        entity.setCallId(record.getCallId());
        entity.setSessionId(record.getSessionId());
        entity.setRunId(record.getRunId());
        entity.setTurnId(record.getTurnId());
        entity.setSourceOrder(record.getSourceOrder());
        entity.setToolName(record.getToolName());
        entity.setStatus(record.getStatus().name());
        entity.setRiskLevel(record.getRiskLevel() == null ? null : record.getRiskLevel().name());
        entity.setRedactedArgs(record.getRedactedArgs());
        entity.setArgsDigest(record.getArgsDigest());
        entity.setIdempotencyKey(record.getIdempotencyKey());
        entity.setActionDigest(record.getActionDigest());
        entity.setResultEventSeq(record.getResultEventSeq());
        entity.setErrorCode(record.getErrorCode());
        entity.setRetryable(record.isRetryable());
        entity.setResultSummary(record.getResultSummary());
        entity.setStartedAt(toLocal(record.getStartedAt()));
        entity.setEndedAt(toLocal(record.getEndedAt()));
        entity.setVersion(record.getVersion());
        return entity;
    }

    private AgentToolCallRecord toToolCallRecord(AiToolCallEntity entity) {
        return AgentToolCallRecord.builder()
                .callId(entity.getCallId())
                .sessionId(entity.getSessionId())
                .runId(entity.getRunId())
                .turnId(entity.getTurnId())
                .sourceOrder(entity.getSourceOrder() == null ? 0 : entity.getSourceOrder())
                .toolName(entity.getToolName())
                .status(enumValue(entity.getStatus(), AgentToolCallStatus.class, AgentToolCallStatus.PROPOSED))
                .riskLevel(entity.getRiskLevel() == null ? null
                        : enumValue(entity.getRiskLevel(), ToolRiskLevel.class, ToolRiskLevel.LOW))
                .redactedArgs(entity.getRedactedArgs())
                .argsDigest(entity.getArgsDigest())
                .idempotencyKey(entity.getIdempotencyKey())
                .actionDigest(entity.getActionDigest())
                .resultEventSeq(entity.getResultEventSeq())
                .errorCode(entity.getErrorCode())
                .retryable(Boolean.TRUE.equals(entity.getRetryable()))
                .resultSummary(entity.getResultSummary())
                .startedAt(toInstant(entity.getStartedAt()))
                .endedAt(toInstant(entity.getEndedAt()))
                .version(entity.getVersion() == null ? 0 : entity.getVersion())
                .build();
    }

    private AiApprovalEntity toApprovalEntity(AgentApprovalRecord record) {
        AiApprovalEntity entity = new AiApprovalEntity();
        entity.setApprovalId(record.getApprovalId());
        entity.setSessionId(record.getSessionId());
        entity.setProposedRunId(record.getProposedRunId());
        entity.setStatus(record.getStatus().name());
        entity.setActionDigest(record.getActionDigest());
        entity.setSafeSummary(record.getSafeSummary());
        entity.setSafeActionsJson(record.getSafeActionsJson());
        entity.setActorId(record.getActorId());
        entity.setDecidedAt(toLocal(record.getDecidedAt()));
        entity.setExpiresAt(toLocal(record.getExpiresAt()));
        entity.setVersion(record.getVersion());
        return entity;
    }

    private AgentApprovalRecord toApprovalRecord(AiApprovalEntity entity) {
        return AgentApprovalRecord.builder()
                .approvalId(entity.getApprovalId())
                .sessionId(entity.getSessionId())
                .proposedRunId(entity.getProposedRunId())
                .status(enumValue(entity.getStatus(), AgentApprovalStatus.class, AgentApprovalStatus.PENDING))
                .actionDigest(entity.getActionDigest())
                .safeSummary(entity.getSafeSummary())
                .safeActionsJson(entity.getSafeActionsJson())
                .actorId(entity.getActorId())
                .decidedAt(toInstant(entity.getDecidedAt()))
                .expiresAt(toInstant(entity.getExpiresAt()))
                .version(entity.getVersion() == null ? 0 : entity.getVersion())
                .build();
    }

    private AiPlanEntity toPlanEntity(AgentProvisioningPlan plan) {
        AiPlanEntity entity = new AiPlanEntity();
        entity.setPlanId(plan.getPlanId());
        entity.setSessionId(plan.getSessionId());
        entity.setObjectiveId(plan.getObjectiveId());
        entity.setStatus(plan.getStatus().name());
        entity.setPlanDigest(plan.getPlanDigest());
        entity.setConfigTemplateDigest(plan.getConfigTemplateDigest());
        entity.setSafeSummary(plan.getSafeSummary());
        entity.setPlanJson(writeJson(plan.withActions(List.of())));
        entity.setVersion(plan.getVersion());
        entity.setCreatedAt(toLocal(plan.getCreatedAt()));
        return entity;
    }

    private AiPlanActionEntity toPlanActionEntity(String planId, AgentPlanAction action) {
        AiPlanActionEntity entity = new AiPlanActionEntity();
        entity.setPlanId(planId);
        entity.setActionId(action.getActionId());
        entity.setSequence(action.getSequence());
        entity.setActionType(action.getActionType().name());
        entity.setName(action.getName());
        entity.setSafeArgsJson(writeJson(action.getSafeArgs() == null ? mapper.createObjectNode()
                : mapper.valueToTree(action.getSafeArgs())));
        entity.setStatus(action.getStatus() == null
                ? AgentPlanActionStatus.PENDING.name() : action.getStatus().name());
        entity.setIdempotencyKey(action.getIdempotencyKey());
        entity.setInputDigest(action.getInputDigest());
        entity.setResourceType(action.getResourceType() == null ? null : action.getResourceType().name());
        entity.setResourceId(action.getResourceId());
        return entity;
    }

    private AgentProvisioningPlan toProvisioningPlan(AiPlanEntity entity, List<AgentPlanAction> actions) {
        return AgentProvisioningPlan.builder()
                .planId(entity.getPlanId())
                .sessionId(entity.getSessionId())
                .objectiveId(entity.getObjectiveId())
                .status(enumValue(entity.getStatus(), AgentPlanStatus.class, AgentPlanStatus.DRAFT))
                .planDigest(entity.getPlanDigest())
                .configTemplateDigest(entity.getConfigTemplateDigest())
                .safeSummary(entity.getSafeSummary())
                .actions(actions)
                .version(entity.getVersion() == null ? 0 : entity.getVersion())
                .createdAt(toInstant(entity.getCreatedAt()))
                .build();
    }

    private AgentPlanAction toPlanAction(AiPlanActionEntity entity) {
        JsonNode safeArgs = readJsonOrEmpty(entity.getSafeArgsJson());
        return AgentPlanAction.builder()
                .actionId(entity.getActionId())
                .sequence(entity.getSequence() == null ? 0 : entity.getSequence())
                .actionType(enumValue(entity.getActionType(), AgentPlanActionType.class,
                        AgentPlanActionType.CREATE_DATASOURCE))
                .name(entity.getName())
                .safeArgs(safeArgs.isObject()
                        ? mapper.convertValue(safeArgs, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        })
                        : Map.of())
                .status(enumValue(entity.getStatus(), AgentPlanActionStatus.class,
                        AgentPlanActionStatus.PENDING))
                .idempotencyKey(entity.getIdempotencyKey())
                .inputDigest(entity.getInputDigest())
                .resourceType(entity.getResourceType() == null ? null
                        : enumValue(entity.getResourceType(), AgentResourceType.class,
                        AgentResourceType.DATASOURCE))
                .resourceId(entity.getResourceId())
                .build();
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot deserialize " + type.getSimpleName(), e);
        }
    }

    private JsonNode readJsonOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    private static <E extends Enum<E>> E enumValue(String name, Class<E> type, E defaultValue) {
        if (name == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime local) {
        return local == null ? null : local.toInstant(ZoneOffset.UTC);
    }
}

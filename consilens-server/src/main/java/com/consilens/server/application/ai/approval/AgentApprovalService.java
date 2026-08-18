package com.consilens.server.application.ai.approval;

import com.consilens.agent.api.store.AgentApprovalDecision;
import com.consilens.agent.api.store.AgentApprovalRecord;
import com.consilens.agent.api.store.AgentApprovalStatus;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.core.security.CanonicalJsonDigester;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Structured approval with parameter digests (design WP-09): an approval is
 * only valid while its digest matches the frozen parameters. Any parameter,
 * secret request or config change invalidates the old approval.
 */
public class AgentApprovalService {

    private final AgentPersistence persistence;

    public AgentApprovalService(AgentPersistence persistence) {
        this.persistence = persistence;
    }

    /**
     * actionDigest = SHA-256(toolName | canonicalArgs | resourceVersion | policyVersion).
     */
    public String computeActionDigest(String toolName,
                                      JsonNode canonicalArgs,
                                      java.util.Map<String, String> resourceVersions,
                                      String policyVersion) {
        return CanonicalJsonDigester.sha256(toolName + "|"
                + CanonicalJsonDigester.canonical(canonicalArgs) + "|"
                + CanonicalJsonDigester.sha256(resourceVersions == null ? ""
                : CanonicalJsonDigester.canonicalMap(resourceVersions)) + "|"
                + policyVersion);
    }

    public AgentApprovalRecord createApproval(String sessionId,
                                              String proposedRunId,
                                              String actionDigest,
                                              String safeSummary,
                                              String safeActionsJson,
                                              Instant expiresAt) {
        return persistence.createApproval(AgentApprovalRecord.builder()
                .approvalId("ap_" + UUID.randomUUID())
                .sessionId(sessionId)
                .proposedRunId(proposedRunId)
                .status(AgentApprovalStatus.PENDING)
                .actionDigest(actionDigest)
                .safeSummary(safeSummary)
                .safeActionsJson(safeActionsJson)
                .expiresAt(expiresAt)
                .build());
    }

    public Optional<AgentApprovalRecord> findPending(String approvalId) {
        return persistence.findApproval(approvalId)
                .filter(a -> a.getStatus() == AgentApprovalStatus.PENDING);
    }

    /**
     * Validates digest + expiry, then CAS-decides. A mismatch returns STALE
     * and never auto-generates a new approval.
     */
    public DecideOutcome decide(String approvalId,
                                String actorId,
                                long expectedVersion,
                                AgentApprovalDecision decision,
                                String submittedDigest,
                                Instant now) {
        AgentApprovalRecord approval = persistence.findApproval(approvalId).orElse(null);
        if (approval == null) {
            return DecideOutcome.stale("APPROVAL_NOT_FOUND");
        }
        if (approval.getStatus() != AgentApprovalStatus.PENDING) {
            return DecideOutcome.stale("APPROVAL_ALREADY_DECIDED");
        }
        if (approval.getExpiresAt() != null && !approval.getExpiresAt().isAfter(now)) {
            return DecideOutcome.stale("APPROVAL_EXPIRED");
        }
        if (submittedDigest == null || !submittedDigest.equals(approval.getActionDigest())) {
            return DecideOutcome.stale("APPROVAL_STALE");
        }
        boolean won = persistence.decideApproval(approvalId, actorId, expectedVersion, decision, now);
        if (!won) {
            return DecideOutcome.stale("APPROVAL_STALE");
        }
        return new DecideOutcome(decision == AgentApprovalDecision.APPROVE
                ? AgentApprovalStatus.APPROVED : AgentApprovalStatus.DENIED, null);
    }

    public static final class DecideOutcome {
        private final AgentApprovalStatus status;
        private final String errorCode;

        DecideOutcome(AgentApprovalStatus status, String errorCode) {
            this.status = status;
            this.errorCode = errorCode;
        }

        public static DecideOutcome stale(String errorCode) {
            return new DecideOutcome(null, errorCode);
        }

        public boolean succeeded() {
            return errorCode == null;
        }

        public AgentApprovalStatus status() {
            return status;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}

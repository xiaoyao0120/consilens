package com.consilens.cluster.api;

import java.time.Duration;

/**
 * Submits a portable, redacted comparison description to one cluster backend.
 */
public interface ClusterSubmitter {

    ClusterSubmission submit(ClusterSubmitRequest request);

    /**
     * Waits until the submitted application reaches a terminal state, mirroring
     * spark-submit's default behaviour of blocking until the application
     * finishes and reporting its final status. Returns null when the submitter
     * cannot observe completion (for example local or simulated runtimes), in
     * which case the caller falls back to the submission acknowledgement.
     */
    default ClusterApplicationResult awaitCompletion(ClusterSubmission submission, Duration timeout) {
        return null;
    }

    /**
     * Requests termination of the submitted application (YARN kill / Kubernetes
     * Job deletion). Implementations must be idempotent; a no-op default keeps
     * submitters without kill support safe to call.
     */
    default void kill(ClusterSubmission submission) {
    }

    /**
     * Best-effort sweep of stale applications from a previous attempt of the
     * same logical submission (recovery restart), locating them through the
     * same namespace / job name / run tag that {@link #submit(ClusterSubmitRequest)}
     * would use. The sweep must not fail the new submission; a no-op for
     * submitters without the capability.
     */
    default void killStale(ClusterSubmitRequest request) {
    }
}

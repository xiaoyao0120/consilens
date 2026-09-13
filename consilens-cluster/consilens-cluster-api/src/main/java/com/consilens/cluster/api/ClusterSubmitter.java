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
}

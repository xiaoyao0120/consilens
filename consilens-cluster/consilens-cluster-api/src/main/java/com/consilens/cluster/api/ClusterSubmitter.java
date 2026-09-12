package com.consilens.cluster.api;

/**
 * Submits a portable, redacted comparison description to one cluster backend.
 */
public interface ClusterSubmitter {

    ClusterSubmission submit(ClusterSubmitRequest request);
}

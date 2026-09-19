package com.consilens.cluster.yarn.gateway;

import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;

import java.net.URI;

/**
 * Boundary around the Hadoop {@code YarnClient} so submission mapping can be tested
 * without a live ResourceManager.
 */
public interface YarnSubmissionGateway extends AutoCloseable {

    ApplicationSubmissionContext createSubmissionContext();

    LocalResource createLocalResource(URI resourceUri, LocalResourceType type);

    URI stageLocalFile(URI localUri, URI stagingBase);

    ApplicationId submit(ApplicationSubmissionContext context);

    /**
     * Default staging directory derived from the submitting user's HDFS home
     * ({@code <home>/.consilens/staging}), mirroring how spark-submit stages
     * under {@code spark.yarn.stagingDir} in the user's home without explicit
     * configuration. Returns null when unknown.
     */
    default String defaultStagingBase() {
        return null;
    }

    /**
     * Current state name of the application, e.g. {@code ACCEPTED},
     * {@code RUNNING}, {@code FINISHED}. Empty when the application is unknown.
     */
    default java.util.Optional<String> applicationState(String applicationId) {
        return java.util.Optional.empty();
    }

    /**
     * Final application status, e.g. {@code SUCCEEDED}; empty until the
     * application reaches a terminal state or when unknown.
     */
    default java.util.Optional<String> applicationFinalStatus(String applicationId) {
        return java.util.Optional.empty();
    }

    /**
     * Tracking URL for the running application; empty when unknown.
     */
    default java.util.Optional<String> trackingUrl(String applicationId) {
        return java.util.Optional.empty();
    }

    /**
     * Requests termination of the application; idempotent.
     */
    default void kill(String applicationId) {
    }

    @Override
    void close();
}

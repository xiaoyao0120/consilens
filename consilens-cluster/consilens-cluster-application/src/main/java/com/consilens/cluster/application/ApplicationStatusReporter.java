package com.consilens.cluster.application;

import com.consilens.cluster.api.ClusterApplicationEnvironment;

import java.util.Map;

/**
 * Reports the outcome of one coordinator run back to the platform that launched
 * it. Kubernetes and direct executions use the no-op reporter, while a YARN
 * ApplicationMaster must register with the ResourceManager and finish with an
 * explicit final status; otherwise the application is marked FAILED regardless
 * of the comparison result.
 */
public interface ApplicationStatusReporter extends AutoCloseable {

    /**
     * Registers the coordinator with its platform before the comparison starts.
     */
    void start();

    /**
     * Marks the comparison as finished successfully. The summary is the JSON
     * document already printed to stdout.
     */
    void reportSucceeded(String summary);

    /**
     * Marks the comparison as failed. The diagnostics must not contain
     * credentials or descriptor content.
     */
    void reportFailed(String diagnostics);

    @Override
    default void close() {
    }

    static ApplicationStatusReporter fromEnvironment(Map<String, String> environment) {
        if (environment == null
                || !ClusterApplicationEnvironment.REPORTER_ENV_YARN
                        .equalsIgnoreCase(environment.get(ClusterApplicationEnvironment.REPORTER_ENV))) {
            return new NoopStatusReporter();
        }
        try {
            return YarnAmStatusReporter.create(environment);
        } catch (LinkageError | RuntimeException e) {
            System.err.println("YARN status reporter unavailable, continuing without reporting: "
                    + e.getClass().getName());
            return new NoopStatusReporter();
        }
    }
}

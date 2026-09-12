package com.consilens.cluster.api;

/**
 * Environment variables shared between the cluster submitter and the
 * ApplicationMaster coordinator runtime.
 */
public final class ClusterApplicationEnvironment {

    /**
     * Marker set by the YARN submitter to activate the AM status reporter.
     */
    public static final String REPORTER_ENV = "CONSILENS_AM_REPORTER";

    /**
     * Value of {@link #REPORTER_ENV} selecting the YARN AM reporter.
     */
    public static final String REPORTER_ENV_YARN = "yarn";

    /**
     * ResourceManager hostname handed to the AM; the AM container classpath
     * does not ship the Hadoop configuration directory, and every RM endpoint
     * (client, scheduler, tracker) derives from this hostname.
     */
    public static final String RM_HOSTNAME_ENV = "CONSILENS_AM_YARN_RM_HOSTNAME";

    private ClusterApplicationEnvironment() {
    }
}

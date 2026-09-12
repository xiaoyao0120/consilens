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
     * ResourceManager hostname from the submission configuration, passed to the
     * ApplicationMaster so every RM endpoint can be derived without shipping the
     * full Hadoop configuration. Returns null when unknown.
     */
    default String resourceManagerHostname() {
        return null;
    }

    @Override
    void close();
}

package com.consilens.cluster.application;

/**
 * Reporter for platforms that derive the outcome from the process exit code,
 * such as direct execution or Kubernetes Jobs.
 */
public class NoopStatusReporter implements ApplicationStatusReporter {

    @Override
    public void start() {
    }

    @Override
    public void reportSucceeded(String summary) {
    }

    @Override
    public void reportFailed(String diagnostics) {
    }
}

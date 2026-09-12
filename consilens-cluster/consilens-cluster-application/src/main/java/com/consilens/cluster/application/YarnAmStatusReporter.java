package com.consilens.cluster.application;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import com.consilens.cluster.api.ClusterApplicationEnvironment;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * YARN ApplicationMaster status reporter backed by {@link AMRMClient}. It
 * registers the AM with the ResourceManager, keeps the application alive with
 * periodic allocate heartbeats while the comparison runs, and finishes the
 * application with an explicit final status.
 */
public class YarnAmStatusReporter implements ApplicationStatusReporter {

    private static final long HEARTBEAT_SECONDS = 5;

    private final AMRMClient<AMRMClient.ContainerRequest> client;
    private final String host;
    private final ScheduledExecutorService heartbeatExecutor;

    static YarnAmStatusReporter create(Map<String, String> environment) {
        Configuration configuration = new YarnConfiguration();
        String resourceManagerHostname = environment.get(ClusterApplicationEnvironment.RM_HOSTNAME_ENV);
        if (resourceManagerHostname != null && !resourceManagerHostname.trim().isEmpty()) {
            configuration.set(YarnConfiguration.RM_HOSTNAME, resourceManagerHostname.trim());
        }
        AMRMClient<AMRMClient.ContainerRequest> client = AMRMClient.createAMRMClient();
        client.init(configuration);
        client.start();
        return new YarnAmStatusReporter(client, containerHost(environment));
    }

    private static String containerHost(Map<String, String> environment) {
        String nodeHost = environment.get("NM_HOST");
        if (nodeHost != null && !nodeHost.trim().isEmpty()) {
            return nodeHost.trim();
        }
        try {
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    YarnAmStatusReporter(AMRMClient<AMRMClient.ContainerRequest> client, String host) {
        this.client = client;
        this.host = host;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "consilens-am-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start() {
        try {
            client.registerApplicationMaster(host, -1, null);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to register with the ResourceManager", e);
        }
        heartbeatExecutor.scheduleWithFixedDelay(this::heartbeat,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    private void heartbeat() {
        try {
            client.allocate(0);
        } catch (Exception e) {
            // The next heartbeat retries; a single failed RPC must not kill the AM.
            System.err.println("AM heartbeat failed: " + e.getClass().getName());
        }
    }

    @Override
    public void reportSucceeded(String summary) {
        finish(FinalApplicationStatus.SUCCEEDED, summary);
    }

    @Override
    public void reportFailed(String diagnostics) {
        finish(FinalApplicationStatus.FAILED, diagnostics);
    }

    private void finish(FinalApplicationStatus status, String diagnostics) {
        try {
            heartbeatExecutor.shutdownNow();
            client.unregisterApplicationMaster(status, diagnostics, null);
        } catch (Exception e) {
            // The attempt outcome is already decided; a failed RPC must not mask it.
            System.err.println("AM unregister failed: " + e.getClass().getName());
        }
    }

    @Override
    public void close() {
        heartbeatExecutor.shutdownNow();
        client.stop();
    }
}

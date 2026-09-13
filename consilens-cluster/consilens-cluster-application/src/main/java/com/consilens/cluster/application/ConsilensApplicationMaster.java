package com.consilens.cluster.application;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * YARN ApplicationMaster wrapper, following the same shape as Spark's
 * {@code org.apache.spark.deploy.yarn.ApplicationMaster}: register with the
 * ResourceManager, keep the application alive with allocate heartbeats, launch
 * the coordinator's main class in a child thread, and finish the application
 * with an explicit final status derived from the coordinator process outcome.
 * The Hadoop configuration (yarn-site.xml, core-site.xml) is shipped into this
 * container by the submitter as localized resources, so the client-side and
 * AM-side views of the cluster endpoints stay identical.
 */
public class ConsilensApplicationMaster {

    private static final long HEARTBEAT_SECONDS = 5;

    private final String[] arguments;
    private final AmClient client;
    private final PrintStream error;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final ScheduledExecutorService heartbeatExecutor;

    interface AmClient extends AutoCloseable {
        void register(String host);

        void allocate();

        void unregister(FinalApplicationStatus status, String diagnostics);

        @Override
        void close();
    }

    public static void main(String[] args) {
        try {
            new ConsilensApplicationMaster(args, new AmRmClientAdapter(new YarnConfiguration()), System.err).run();
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid ApplicationMaster arguments: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("ApplicationMaster setup failed: " + e.getClass().getName());
            System.exit(3);
        }
    }

    ConsilensApplicationMaster(String[] arguments, AmClient client, PrintStream error) {
        this.arguments = arguments == null ? new String[0] : arguments;
        this.client = client;
        this.error = error;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "consilens-am-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    void run() throws Exception {
        AmOptions options = AmOptions.parse(arguments);
        // A non-zero coordinator exit calls System.exit directly; this hook then
        // reports FAILED, mirroring Spark's AM shutdown hook behaviour.
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                finish(FinalApplicationStatus.FAILED, "Coordinator process exited with a non-zero status"),
                "consilens-am-shutdown"));
        client.register(containerHost());
        heartbeatExecutor.scheduleWithFixedDelay(this::heartbeat,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        Thread coordinatorThread = launchCoordinator(options);
        coordinatorThread.join();
        finish(FinalApplicationStatus.SUCCEEDED, null);
        cleanupStaging(options.stagingDir);
    }

    private Thread launchCoordinator(AmOptions options) {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean launched = new AtomicBoolean();
        Thread coordinatorThread = new Thread(() -> {
            try {
                Class<?> coordinatorClass = Class.forName(options.coordinatorClass);
                Method main = coordinatorClass.getMethod("main", String[].class);
                started.countDown();
                launched.set(true);
                main.invoke(null, (Object) options.coordinatorArguments());
            } catch (Throwable e) {
                started.countDown();
                if (!launched.get()) {
                    error.println("Unable to launch coordinator " + options.coordinatorClass
                            + ": " + e.getClass().getName());
                }
            }
        }, "consilens-coordinator");
        coordinatorThread.start();
        try {
            started.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(3);
        }
        if (!launched.get()) {
            System.exit(3);
        }
        return coordinatorThread;
    }

    private void heartbeat() {
        try {
            client.allocate();
        } catch (Exception e) {
            // The next heartbeat retries; a single failed RPC must not kill the AM.
            error.println("AM heartbeat failed: " + e.getClass().getName());
        }
    }

    private void finish(FinalApplicationStatus status, String diagnostics) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        heartbeatExecutor.shutdownNow();
        client.unregister(status, diagnostics);
        client.close();
    }

    private void cleanupStaging(String stagingDir) {
        if (stagingDir == null || stagingDir.isBlank()) {
            return;
        }
        try {
            Path path = new Path(stagingDir);
            FileSystem fileSystem = path.getFileSystem(new Configuration());
            fileSystem.delete(path, true);
        } catch (Exception e) {
            // Cleanup is best effort; a failed delete must not mask the result.
            error.println("Staging cleanup failed: " + e.getClass().getName());
        }
    }

    private String containerHost() {
        String nodeHost = System.getenv("NM_HOST");
        if (nodeHost != null && !nodeHost.trim().isEmpty()) {
            return nodeHost.trim();
        }
        try {
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    private static final class AmOptions {
        private final String coordinatorClass;
        private final String descriptor;
        private final String stagingDir;

        private AmOptions(String coordinatorClass, String descriptor, String stagingDir) {
            this.coordinatorClass = coordinatorClass;
            this.descriptor = descriptor;
            this.stagingDir = stagingDir;
        }

        static AmOptions parse(String[] arguments) {
            String coordinatorClass = null;
            String descriptor = null;
            String stagingDir = null;
            for (int i = 0; i < arguments.length; i += 2) {
                if (i + 1 >= arguments.length) {
                    throw new IllegalArgumentException("option without value: " + arguments[i]);
                }
                String value = arguments[i + 1];
                if ("--coordinator-class".equals(arguments[i])) {
                    coordinatorClass = value;
                } else if ("--descriptor".equals(arguments[i])) {
                    descriptor = value;
                } else if ("--staging-dir".equals(arguments[i])) {
                    stagingDir = value;
                } else {
                    throw new IllegalArgumentException("unknown option: " + arguments[i]);
                }
            }
            if (coordinatorClass == null || descriptor == null) {
                throw new IllegalArgumentException("--coordinator-class and --descriptor are required");
            }
            return new AmOptions(coordinatorClass, descriptor, stagingDir);
        }

        String[] coordinatorArguments() {
            return new String[]{"consilens", descriptor};
        }
    }

    static final class AmRmClientAdapter implements AmClient {

        private final AMRMClient<AMRMClient.ContainerRequest> delegate;

        AmRmClientAdapter(Configuration configuration) {
            this.delegate = AMRMClient.createAMRMClient();
            delegate.init(configuration);
            delegate.start();
        }

        @Override
        public void register(String host) {
            try {
                delegate.registerApplicationMaster(host, -1, null);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to register with the ResourceManager", e);
            }
        }

        @Override
        public void allocate() {
            try {
                delegate.allocate(0);
            } catch (Exception e) {
                throw new IllegalStateException("AM allocate failed", e);
            }
        }

        @Override
        public void unregister(FinalApplicationStatus status, String diagnostics) {
            try {
                delegate.unregisterApplicationMaster(status, diagnostics, null);
            } catch (Exception e) {
                // The attempt outcome is already decided; a failed RPC must not mask it.
                System.err.println("AM unregister failed: " + e.getClass().getName());
            }
        }

        @Override
        public void close() {
            delegate.stop();
        }
    }
}

package com.consilens.cluster.yarn;

import com.consilens.cluster.api.ClusterApplicationResult;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.cluster.api.SubmissionReference;
import com.consilens.cluster.api.YarnSubmissionSpec;
import com.consilens.cluster.yarn.gateway.HadoopYarnSubmissionGateway;
import com.consilens.cluster.yarn.gateway.YarnSubmissionGateway;
import com.consilens.connector.api.planner.ExecutionMode;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.conf.YarnConfiguration;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * YARN implementation of the cluster submission SPI, modelled on Spark's yarn
 * {@code Client}: the client-side Hadoop configuration directory is localized
 * into the AM container (the {@code __spark_conf__} pattern), artifacts are
 * staged under a per-application staging directory ({@code <staging>/<appId>/}),
 * and the AM command launches a fixed ApplicationMaster wrapper —
 * {@link com.consilens.cluster.application.ConsilensApplicationMaster} — with
 * the coordinator class supplied as an argument, mirroring how
 * {@code ApplicationMaster --class <userClass>} wraps the Spark driver.
 * Only the runtime archive and the redacted descriptor URI are exposed as
 * local resources; CLI YAML content is never uploaded.
 */
public class YarnClusterSubmitter implements ClusterSubmitter, AutoCloseable {

    static final String AM_MAIN_CLASS = "com.consilens.cluster.application.ConsilensApplicationMaster";
    private static final String RUNTIME_ARCHIVE_DESTINATION = "consilens-runtime";
    private static final String RUNTIME_JAR_DESTINATION = "consilens-runtime.jar";
    private static final String DESCRIPTOR_DESTINATION_PREFIX = "submission-descriptor";
    private static final String SECRET_ENVIRONMENT_DESTINATION = "submission-secrets.properties";
    private static final String HADOOP_CONF_DESTINATION_PREFIX = "hadoop-conf/";
    private static final String DEFAULT_QUEUE = "default";
    private static final long COMPLETION_POLL_SECONDS = 3;
    private static final Pattern SAFE_SUBMISSION_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final YarnSubmissionGateway gateway;
    private final Path hadoopConfDirectory;

    public YarnClusterSubmitter() {
        this(new HadoopYarnSubmissionGateway(new YarnConfiguration()), null);
    }

    public YarnClusterSubmitter(YarnSubmissionGateway gateway) {
        this(gateway, null);
    }

    YarnClusterSubmitter(YarnSubmissionGateway gateway, Path hadoopConfDirectory) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.hadoopConfDirectory = hadoopConfDirectory;
    }

    @Override
    public ClusterSubmission submit(ClusterSubmitRequest request) {
        YarnSubmissionSpec spec = validateAndExtractSpec(request);
        int maxAttempts = maxAttemptsOrDefault(request);
        String applicationStagingDir = applicationStagingDir(request.getSubmissionId(), spec);
        YarnSubmissionSpec resolvedSpec = StageResolver.resolve(spec, applicationStagingDir, gateway);
        ApplicationSubmissionContext context = gateway.createSubmissionContext();
        mapSubmissionContext(context, request, resolvedSpec, maxAttempts, applicationStagingDir);
        ApplicationId applicationId = gateway.submit(context);
        return ClusterSubmission.builder()
                .submissionId(request.getSubmissionId())
                .executionMode(ExecutionMode.YARN)
                .submittedAt(Instant.now())
                .clusterApplicationId(applicationId.toString())
                .build();
    }

    @Override
    public ClusterApplicationResult awaitCompletion(ClusterSubmission submission, Duration timeout) {
        if (submission == null || submission.getClusterApplicationId() == null) {
            return null;
        }
        String applicationId = submission.getClusterApplicationId();
        long deadlineNanos = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < deadlineNanos) {
                String state = gateway.applicationState(applicationId).orElse(null);
                if ("FINISHED".equals(state) || "FAILED".equals(state) || "KILLED".equals(state)) {
                    String finalStatus = gateway.applicationFinalStatus(applicationId).orElse(state);
                    return new ClusterApplicationResult(applicationId, finalStatus,
                            gateway.trackingUrl(applicationId).orElse(null), null);
                }
                Thread.sleep(COMPLETION_POLL_SECONDS * 1000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    @Override
    public void close() {
        gateway.close();
    }

    private YarnSubmissionSpec validateAndExtractSpec(ClusterSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("YARN submission request is required");
        }
        request.validate();
        if (request.getExecution().getExecutionMode() != ExecutionMode.YARN) {
            throw new IllegalArgumentException("Yarn submitter only supports YARN execution mode");
        }
        YarnSubmissionSpec spec = request.getYarnSubmission();
        if (spec == null) {
            throw new IllegalArgumentException("yarnSubmission is required for YARN submission");
        }
        applyDefaultStaging(spec);
        spec.validate();
        if (!SAFE_SUBMISSION_ID.matcher(request.getSubmissionId()).matches()) {
            throw new IllegalArgumentException("submissionId must match [A-Za-z0-9_-]+");
        }
        return spec;
    }

    private void mapSubmissionContext(ApplicationSubmissionContext context,
                                      ClusterSubmitRequest request,
                                      YarnSubmissionSpec spec,
                                      int maxAttempts,
                                      String applicationStagingDir) {
        context.setApplicationName(spec.getApplicationName());
        context.setQueue(queueOrDefault(spec.getQueue()));
        context.setResource(Resource.newInstance(spec.getAmMemoryMb(), spec.getAmVCores()));
        if (maxAttempts > 0) {
            context.setMaxAppAttempts(maxAttempts);
        }
        // -1 keeps the cluster-wide RM default (spark.yarn.maxAppAttempts behaviour).
        context.setApplicationTags(Set.copyOf(spec.sanitizedTags()));
        context.setAMContainerSpec(buildContainerLaunchContext(request, spec, applicationStagingDir));
    }

    private ContainerLaunchContext buildContainerLaunchContext(ClusterSubmitRequest request,
                                                              YarnSubmissionSpec spec,
                                                              String applicationStagingDir) {
        Map<String, LocalResource> localResources = new LinkedHashMap<>();
        String runtimeDestination = runtimeDestination(spec.getRuntimeArchiveUri());
        localResources.put(runtimeDestination, gateway.createLocalResource(
                URI.create(spec.getRuntimeArchiveUri()), runtimeLocalResourceType(spec.getRuntimeArchiveUri())));
        String descriptorDestination = descriptorDestination(spec);
        localResources.put(descriptorDestination, gateway.createLocalResource(
                URI.create(spec.getDescriptorUri()), LocalResourceType.FILE));
        if (spec.getSecretEnvironmentUri() != null) {
            localResources.put(SECRET_ENVIRONMENT_DESTINATION, gateway.createLocalResource(
                    URI.create(spec.getSecretEnvironmentUri()), LocalResourceType.FILE));
        }
        localizeReferences(localResources, spec.getFiles());
        localizeReferences(localResources, spec.getJars());
        localizeHadoopConfiguration(localResources);
        List<String> commands = List.of(buildAmCommand(request, spec, descriptorDestination, applicationStagingDir));
        return ContainerLaunchContext.newInstance(localResources, null, commands, null, null, null);
    }

    /**
     * Localizes the client-side Hadoop configuration into the AM container so it
     * sees the same ResourceManager endpoints as the submitter, exactly like
     * Spark ships {@code HADOOP_CONF_DIR} as the {@code __spark_conf__} resource.
     */
    private void localizeHadoopConfiguration(Map<String, LocalResource> localResources) {
        Path confDirectory = resolveHadoopConfDirectory();
        if (confDirectory == null) {
            throw new IllegalStateException("Hadoop configuration directory is required for YARN submission; "
                    + "set HADOOP_CONF_DIR or put yarn-site.xml on the classpath");
        }
        File[] confFiles = confDirectory.toFile().listFiles(File::isFile);
        if (confFiles == null || confFiles.length == 0) {
            throw new IllegalStateException("Hadoop configuration directory contains no files: " + confDirectory);
        }
        for (File confFile : confFiles) {
            localResources.put(HADOOP_CONF_DESTINATION_PREFIX + confFile.getName(), gateway.createLocalResource(
                    confFile.toURI(), LocalResourceType.FILE));
        }
    }

    private Path resolveHadoopConfDirectory() {
        if (hadoopConfDirectory != null) {
            return hadoopConfDirectory;
        }
        String configuredDirectory = System.getenv("HADOOP_CONF_DIR");
        if (configuredDirectory != null && !configuredDirectory.trim().isEmpty()) {
            return Path.of(configuredDirectory.trim());
        }
        URL yarnSite = Thread.currentThread().getContextClassLoader().getResource("yarn-site.xml");
        if (yarnSite != null && "file".equals(yarnSite.getProtocol())) {
            return Path.of(URI.create(yarnSite.toString()).getPath()).getParent();
        }
        return null;
    }

    private String buildAmCommand(ClusterSubmitRequest request,
                                  YarnSubmissionSpec spec,
                                  String descriptorDestination,
                                  String applicationStagingDir) {
        return "$JAVA_HOME/bin/java -server -Xmx" + spec.getAmMemoryMb() + "m -cp \"$PWD:hadoop-conf:"
                + runtimeClasspath(spec) + "\" " + AM_MAIN_CLASS
                + " --coordinator-class " + spec.getAmMainClass()
                + " --descriptor " + descriptorDestination
                + secretEnvironmentArgument(spec)
                + stagingDirArgument(applicationStagingDir);
    }

    private String stagingDirArgument(String applicationStagingDir) {
        return applicationStagingDir == null ? "" : " --staging-dir " + applicationStagingDir;
    }

    /**
     * Per-application staging directory {@code <staging>/<submissionId>/}, the
     * same layout Spark uses under {@code spark.yarn.stagingDir}. The
     * ApplicationMaster deletes it after a successful finish.
     */
    private String applicationStagingDir(String submissionId, YarnSubmissionSpec spec) {
        if (spec.getStagingUri() == null) {
            return null;
        }
        String base = spec.getStagingUri();
        return (base.endsWith("/") ? base : base + "/") + submissionId;
    }

    /**
     * Falls back to the submitting user's HDFS home staging directory when a
     * local artifact needs staging and no explicit staging URI was provided,
     * mirroring spark-submit which stages under {@code spark.yarn.stagingDir}
     * in the user's home by default. All-remote submissions skip staging
     * entirely.
     */
    private void applyDefaultStaging(YarnSubmissionSpec spec) {
        if (spec.getStagingUri() != null || !hasLocalArtifacts(spec)) {
            return;
        }
        String defaultBase = gateway.defaultStagingBase();
        if (defaultBase != null) {
            spec.setStagingUri(defaultBase);
        }
    }

    private boolean hasLocalArtifacts(YarnSubmissionSpec spec) {
        if (isLocalReference(spec.getRuntimeArchiveUri())
                || isLocalReference(spec.getDescriptorUri())
                || isLocalReference(spec.getSecretEnvironmentUri())) {
            return true;
        }
        return anyLocalReference(spec.getFiles()) || anyLocalReference(spec.getJars());
    }

    private boolean anyLocalReference(List<String> references) {
        if (references == null) {
            return false;
        }
        for (String reference : references) {
            if (isLocalReference(YarnSubmissionSpec.referenceUri(reference))) {
                return true;
            }
        }
        return false;
    }

    private boolean isLocalReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return false;
        }
        try {
            return SubmissionReference.isLocal(URI.create(reference));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Localizes {@code --files}/{@code --jars} references by their file name
     * (or {@code #alias}), the same visibility rule as spark-submit: the files
     * appear in the container working directory under that name.
     */
    private void localizeReferences(Map<String, LocalResource> localResources, List<String> references) {
        if (references == null) {
            return;
        }
        for (String reference : references) {
            localResources.put(localizedFileName(reference), gateway.createLocalResource(
                    URI.create(YarnSubmissionSpec.referenceUri(reference)), LocalResourceType.FILE));
        }
    }

    private String localizedFileName(String reference) {
        String alias = YarnSubmissionSpec.referenceAlias(reference);
        if (alias != null && !alias.isBlank()) {
            return alias;
        }
        String path = URI.create(YarnSubmissionSpec.referenceUri(reference)).getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private String runtimeDestination(String runtimeUri) {
        return isJar(runtimeUri) ? RUNTIME_JAR_DESTINATION : RUNTIME_ARCHIVE_DESTINATION;
    }

    private LocalResourceType runtimeLocalResourceType(String runtimeUri) {
        return isJar(runtimeUri) ? LocalResourceType.FILE : LocalResourceType.ARCHIVE;
    }

    private String runtimeClasspath(YarnSubmissionSpec spec) {
        String runtime = isJar(spec.getRuntimeArchiveUri())
                ? RUNTIME_JAR_DESTINATION : RUNTIME_ARCHIVE_DESTINATION + "/*";
        // User jars are appended after the runtime so runtime classes win,
        // mirroring how spark-submit loads user jars below its own libraries.
        StringBuilder classpath = new StringBuilder(runtime);
        if (spec.getJars() != null) {
            for (String jar : spec.getJars()) {
                classpath.append(':').append(localizedFileName(jar));
            }
        }
        return classpath.toString();
    }

    private boolean isJar(String runtimeUri) {
        String path = URI.create(runtimeUri).getPath();
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private String descriptorDestination(YarnSubmissionSpec spec) {
        String path = URI.create(spec.getDescriptorUri()).getPath();
        String lowerCasePath = path.toLowerCase(Locale.ROOT);
        if (lowerCasePath.endsWith(".yaml")) {
            return DESCRIPTOR_DESTINATION_PREFIX + ".yaml";
        }
        if (lowerCasePath.endsWith(".yml")) {
            return DESCRIPTOR_DESTINATION_PREFIX + ".yml";
        }
        return DESCRIPTOR_DESTINATION_PREFIX + ".json";
    }

    private String secretEnvironmentArgument(YarnSubmissionSpec spec) {
        return spec.getSecretEnvironmentUri() == null ? "" : " --secrets " + SECRET_ENVIRONMENT_DESTINATION;
    }

    private String queueOrDefault(String queue) {
        return queue == null || queue.trim().isEmpty() ? DEFAULT_QUEUE : queue;
    }

    private int maxAttemptsOrDefault(ClusterSubmitRequest request) {
        Integer maxAttempts = request.getExecution().getMaxAttempts();
        if (maxAttempts == null) {
            return -1;
        }
        if (maxAttempts == 0 || maxAttempts < -1) {
            throw new IllegalArgumentException("maxAttempts must be positive or -1 to use the cluster default");
        }
        return maxAttempts;
    }

    /**
     * Replaces local artifact references with their staged remote URIs before a
     * submission context reaches the ResourceManager. Staged files keep their
     * original names inside the per-application staging directory.
     */
    private static final class StageResolver {

        private StageResolver() {
        }

        private static YarnSubmissionSpec resolve(YarnSubmissionSpec spec,
                                                  String applicationStagingDir,
                                                  YarnSubmissionGateway gateway) {
            YarnSubmissionSpec staged = YarnSubmissionSpec.builder()
                    .runtimeArchiveUri(stageIfLocal(spec.getRuntimeArchiveUri(), applicationStagingDir, gateway))
                    .descriptorUri(stageIfLocal(spec.getDescriptorUri(), applicationStagingDir, gateway))
                    .secretEnvironmentUri(stageIfPresent(spec.getSecretEnvironmentUri(), applicationStagingDir, gateway))
                    .stagingUri(spec.getStagingUri())
                    .amMainClass(spec.getAmMainClass())
                    .applicationName(spec.getApplicationName())
                    .queue(spec.getQueue())
                    .amMemoryMb(spec.getAmMemoryMb())
                    .amVCores(spec.getAmVCores())
                    .tags(spec.sanitizedTags())
                    .files(stageReferences(spec.getFiles(), applicationStagingDir, gateway))
                    .jars(stageReferences(spec.getJars(), applicationStagingDir, gateway))
                    .build();
            staged.validate();
            return staged;
        }

        /**
         * Stages the URI part of local {@code uri#alias} references and keeps
         * the alias suffix intact, so remote references pass through untouched.
         */
        private static List<String> stageReferences(List<String> references,
                                                    String applicationStagingDir,
                                                    YarnSubmissionGateway gateway) {
            if (references == null || references.isEmpty()) {
                return references;
            }
            List<String> staged = new ArrayList<>();
            for (String reference : references) {
                String uriPart = YarnSubmissionSpec.referenceUri(reference);
                String alias = YarnSubmissionSpec.referenceAlias(reference);
                String resolved = stageIfLocal(uriPart, applicationStagingDir, gateway);
                staged.add(alias == null ? resolved : resolved + "#" + alias);
            }
            return staged;
        }

        private static String stageIfPresent(String value, String applicationStagingDir, YarnSubmissionGateway gateway) {
            return value == null ? null : stageIfLocal(value, applicationStagingDir, gateway);
        }

        private static String stageIfLocal(String value, String applicationStagingDir, YarnSubmissionGateway gateway) {
            URI uri = URI.create(value);
            if (!SubmissionReference.isLocal(uri)) {
                return value;
            }
            URI localPath = SubmissionReference.toLocalPath(value).toUri();
            return gateway.stageLocalFile(localPath, URI.create(applicationStagingDir)).toString();
        }
    }
}

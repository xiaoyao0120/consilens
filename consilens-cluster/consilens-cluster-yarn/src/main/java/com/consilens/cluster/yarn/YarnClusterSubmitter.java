package com.consilens.cluster.yarn;

import com.consilens.cluster.api.ClusterApplicationEnvironment;
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

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * YARN implementation of the cluster submission SPI. It maps a redacted portable
 * request to an {@link ApplicationSubmissionContext} and submits it through the
 * configured gateway. Only the runtime archive and the redacted descriptor URI are
 * exposed as local resources; CLI YAML content is never uploaded.
 */
public class YarnClusterSubmitter implements ClusterSubmitter, AutoCloseable {

    private static final String RUNTIME_ARCHIVE_DESTINATION = "consilens-runtime";
    private static final String RUNTIME_JAR_DESTINATION = "consilens-runtime.jar";
    private static final String DESCRIPTOR_DESTINATION_PREFIX = "submission-descriptor";
    private static final String SECRET_ENVIRONMENT_DESTINATION = "submission-secrets.properties";
    private static final String DEFAULT_QUEUE = "default";
    private static final Pattern SAFE_SUBMISSION_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final YarnSubmissionGateway gateway;

    public YarnClusterSubmitter() {
        this(new HadoopYarnSubmissionGateway(new YarnConfiguration()));
    }

    public YarnClusterSubmitter(YarnSubmissionGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public ClusterSubmission submit(ClusterSubmitRequest request) {
        YarnSubmissionSpec spec = validateAndExtractSpec(request);
        int maxAttempts = maxAttemptsOrDefault(request);
        YarnSubmissionSpec resolvedSpec = StageResolver.resolve(spec, gateway);
        ApplicationSubmissionContext context = gateway.createSubmissionContext();
        mapSubmissionContext(context, request, resolvedSpec, maxAttempts);
        ApplicationId applicationId = gateway.submit(context);
        return ClusterSubmission.builder()
                .submissionId(request.getSubmissionId())
                .executionMode(ExecutionMode.YARN)
                .submittedAt(Instant.now())
                .clusterApplicationId(applicationId.toString())
                .build();
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
        spec.validate();
        if (!SAFE_SUBMISSION_ID.matcher(request.getSubmissionId()).matches()) {
            throw new IllegalArgumentException("submissionId must match [A-Za-z0-9_-]+");
        }
        return spec;
    }

    private void mapSubmissionContext(ApplicationSubmissionContext context,
                                      ClusterSubmitRequest request,
                                      YarnSubmissionSpec spec,
                                      int maxAttempts) {
        context.setApplicationName(spec.getApplicationName());
        context.setQueue(queueOrDefault(spec.getQueue()));
        context.setResource(Resource.newInstance(spec.getAmMemoryMb(), spec.getAmVCores()));
        context.setMaxAppAttempts(maxAttempts);
        context.setApplicationTags(Set.copyOf(spec.sanitizedTags()));
        context.setAMContainerSpec(buildContainerLaunchContext(request, spec));
    }

    private ContainerLaunchContext buildContainerLaunchContext(ClusterSubmitRequest request,
                                                              YarnSubmissionSpec spec) {
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
        List<String> commands = List.of(buildAmCommand(request, spec, descriptorDestination));
        return ContainerLaunchContext.newInstance(localResources, buildAmEnvironment(), commands,
                null, null, null);
    }

    /**
     * Activates the AM-side status reporter and hands it the ResourceManager
     * address; the AM container classpath does not ship the Hadoop conf.
     */
    private Map<String, String> buildAmEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(ClusterApplicationEnvironment.REPORTER_ENV,
                ClusterApplicationEnvironment.REPORTER_ENV_YARN);
        String resourceManagerHostname = gateway.resourceManagerHostname();
        if (resourceManagerHostname != null && !resourceManagerHostname.trim().isEmpty()) {
            environment.put(ClusterApplicationEnvironment.RM_HOSTNAME_ENV, resourceManagerHostname.trim());
        }
        return environment;
    }

    private String buildAmCommand(ClusterSubmitRequest request, YarnSubmissionSpec spec, String descriptorDestination) {
        return "java -Xmx" + spec.getAmMemoryMb() + "m -cp " + runtimeClasspath(spec.getRuntimeArchiveUri())
                + " " + spec.getAmMainClass() + " " + request.getSubmissionId() + " " + descriptorDestination
                + secretEnvironmentArgument(spec);
    }

    private String runtimeDestination(String runtimeUri) {
        return isJar(runtimeUri) ? RUNTIME_JAR_DESTINATION : RUNTIME_ARCHIVE_DESTINATION;
    }

    private LocalResourceType runtimeLocalResourceType(String runtimeUri) {
        return isJar(runtimeUri) ? LocalResourceType.FILE : LocalResourceType.ARCHIVE;
    }

    private String runtimeClasspath(String runtimeUri) {
        return isJar(runtimeUri) ? RUNTIME_JAR_DESTINATION : RUNTIME_ARCHIVE_DESTINATION + "/*";
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
        return spec.getSecretEnvironmentUri() == null ? "" : " " + SECRET_ENVIRONMENT_DESTINATION;
    }

    private String queueOrDefault(String queue) {
        return queue == null || queue.trim().isEmpty() ? DEFAULT_QUEUE : queue;
    }

    private int maxAttemptsOrDefault(ClusterSubmitRequest request) {
        Integer maxAttempts = request.getExecution().getMaxAttempts();
        if (maxAttempts == null) {
            return 1;
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive for YARN submission");
        }
        return maxAttempts;
    }

    /**
     * Replaces local artifact references with their staged remote URIs before a
     * submission context reaches the ResourceManager.
     */
    private static final class StageResolver {

        private StageResolver() {
        }

        private static YarnSubmissionSpec resolve(YarnSubmissionSpec spec, YarnSubmissionGateway gateway) {
            YarnSubmissionSpec staged = YarnSubmissionSpec.builder()
                    .runtimeArchiveUri(stageIfLocal(spec.getRuntimeArchiveUri(), spec.getStagingUri(), gateway))
                    .descriptorUri(stageIfLocal(spec.getDescriptorUri(), spec.getStagingUri(), gateway))
                    .secretEnvironmentUri(stageIfPresent(spec.getSecretEnvironmentUri(), spec.getStagingUri(), gateway))
                    .stagingUri(spec.getStagingUri())
                    .amMainClass(spec.getAmMainClass())
                    .applicationName(spec.getApplicationName())
                    .queue(spec.getQueue())
                    .amMemoryMb(spec.getAmMemoryMb())
                    .amVCores(spec.getAmVCores())
                    .tags(spec.sanitizedTags())
                    .build();
            staged.validate();
            return staged;
        }

        private static String stageIfPresent(String value, String stagingUri, YarnSubmissionGateway gateway) {
            return value == null ? null : stageIfLocal(value, stagingUri, gateway);
        }

        private static String stageIfLocal(String value, String stagingUri, YarnSubmissionGateway gateway) {
            URI uri = URI.create(value);
            if (!SubmissionReference.isLocal(uri)) {
                return value;
            }
            URI localPath = SubmissionReference.toLocalPath(value).toUri();
            return gateway.stageLocalFile(localPath, URI.create(stagingUri)).toString();
        }
    }
}

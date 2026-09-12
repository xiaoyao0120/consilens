package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.cluster.api.ClusterComparisonDescription;
import com.consilens.cluster.api.ClusterExecutionDescription;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.cluster.api.YarnSubmissionSpec;
import com.consilens.connector.api.planner.ExecutionMode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Submits one compare request to a YARN cluster through the public cluster
 * submission SPI. The descriptor is parsed only inside the ApplicationMaster,
 * so submit hosts do not need connector credentials.
 */
@Command(
        name = "yarn",
        description = "Submit a comparison to YARN",
        mixinStandardHelpOptions = true
)
public class SubmitYarnCommand implements Callable<Integer> {

    private final Supplier<ClusterSubmitter> submitterFactory;
    private final Supplier<String> submissionIdFactory;

    @Option(names = {"-c", "--config"},
            description = "Optional local descriptor reference retained for compatibility; cluster runtime reads --descriptor-uri")
    private String configFile;

    @Option(names = "--runtime-archive", required = true,
            description = "Remote URI or local path of the AM runtime archive; local paths use --staging-uri")
    private String runtimeArchiveUri;

    @Option(names = "--descriptor-uri", required = true,
            description = "Remote URI or local path of the redacted submission descriptor; local paths use --staging-uri")
    private String descriptorUri;

    @Option(names = "--secret-env-file",
            description = "Remote URI or local path of a protected Java properties file used for runtime ${env.NAME} resolution")
    private String secretEnvironmentUri;

    @Option(names = "--staging-uri",
            description = "Remote staging directory for local runtime archive, descriptor, or secret files")
    private String stagingUri;

    @Option(names = "--am-class", defaultValue = "com.consilens.cluster.application.ClusterComparisonCoordinator",
            description = "Application main class supplied by the runtime archive")
    private String amMainClass;

    @Option(names = "--queue", description = "YARN queue (default: default)")
    private String queue;

    @Option(names = "--name", description = "Application name (default: consilens-<submission id>)")
    private String applicationName;

    @Option(names = "--am-memory", defaultValue = "1024",
            description = "AM container memory in MB (default: 1024)")
    private int amMemoryMb;

    @Option(names = "--am-vcores", defaultValue = "1",
            description = "AM container virtual cores (default: 1)")
    private int amVCores;

    @Option(names = "--tag", description = "Application tag; repeatable")
    private List<String> tags = new ArrayList<>();

    @Option(names = "--max-attempts", defaultValue = "1",
            description = "Maximum application attempts (default: 1)")
    private int maximumAttempts;

    @Spec
    private CommandSpec commandSpec;

    public SubmitYarnCommand() {
        this(SubmitYarnCommand::createDefaultSubmitter, () -> UUID.randomUUID().toString());
    }

    SubmitYarnCommand(Supplier<ClusterSubmitter> submitterFactory,
                      Supplier<String> submissionIdFactory) {
        this.submitterFactory = submitterFactory;
        this.submissionIdFactory = submissionIdFactory;
    }

    SubmitYarnCommand(ConfigurationManager configurationManager,
                      CompareRequestFactory compareRequestFactory,
                      Supplier<ClusterSubmitter> submitterFactory,
                      Supplier<String> submissionIdFactory) {
        this(submitterFactory, submissionIdFactory);
    }

    @Override
    public Integer call() {
        ClusterSubmitter submitter = null;
        try {
            String submissionId = submissionIdFactory.get();
            ClusterSubmitRequest submissionRequest = ClusterSubmitRequest.builder()
                    .submissionId(submissionId)
                    .comparison(ClusterComparisonDescription.builder()
                            .sourceConfigRef("descriptor:" + descriptorUri)
                            .targetConfigRef("descriptor:" + descriptorUri)
                            .build())
                    .execution(ClusterExecutionDescription.builder()
                            .executionMode(ExecutionMode.YARN)
                            .maxAttempts(maximumAttempts)
                            .yarnQueue(queue)
                            .build())
                    .build();
            submissionRequest.setYarnSubmission(YarnSubmissionSpec.builder()
                    .runtimeArchiveUri(runtimeArchiveUri)
                    .descriptorUri(descriptorUri)
                    .secretEnvironmentUri(secretEnvironmentUri)
                    .stagingUri(stagingUri)
                    .amMainClass(amMainClass)
                    .applicationName(applicationNameOrDefault(submissionId))
                    .queue(queue)
                    .amMemoryMb(amMemoryMb)
                    .amVCores(amVCores)
                    .tags(tags)
                    .build());
            validateSubmissionRequest(submissionRequest);
            submitter = submitterFactory.get();
            ClusterSubmission submission = submitter.submit(submissionRequest);
            commandSpec.commandLine().getOut().println(
                    "YARN application submitted: " + submission.getClusterApplicationId());
            return 0;
        } catch (Exception e) {
            commandSpec.commandLine().getErr().println("YARN submission failed.");
            return 1;
        } finally {
            closeQuietly(submitter);
        }
    }

    private void validateSubmissionRequest(ClusterSubmitRequest submissionRequest) {
        submissionRequest.validate();
        submissionRequest.getYarnSubmission().validate();
        if (maximumAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive for YARN submission");
        }
    }

    private String applicationNameOrDefault(String submissionId) {
        return applicationName == null || applicationName.trim().isEmpty()
                ? "consilens-" + submissionId
                : applicationName;
    }

    /**
     * Keep Hadoop out of the ordinary CLI runtime. Picocli constructs all child
     * commands while rendering help, so the YARN backend must be loaded only when
     * this command is actually executed.
     */
    private static ClusterSubmitter createDefaultSubmitter() {
        try {
            Class<?> type = Class.forName("com.consilens.cluster.yarn.YarnClusterSubmitter");
            return (ClusterSubmitter) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("YARN client runtime is unavailable; add Hadoop client libraries", e);
        }
    }

    private void closeQuietly(ClusterSubmitter submitter) {
        if (submitter instanceof AutoCloseable) {
            try {
                ((AutoCloseable) submitter).close();
            } catch (Exception ignored) {
                // Submission outcome is already reported; cleanup failure is not fatal.
            }
        }
    }
}

package com.consilens.cli.ai.runtime;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Filesystem layout for the CLI-backed AI runtime.
 */
public class AiRuntimePaths {

    private final Path baseDir;
    private final Path installHome;

    public AiRuntimePaths() {
        this(System.getenv("CONSILENS_AI_HOME"), System.getProperty("consilens.examples.dir"));
    }

    AiRuntimePaths(String configuredBaseDir) {
        this(configuredBaseDir, null);
    }

    AiRuntimePaths(String configuredBaseDir, String examplesDir) {
        this.baseDir = configuredBaseDir == null || configuredBaseDir.trim().isEmpty()
                ? Paths.get(System.getProperty("user.home"), ".consilens", "ai")
                : Paths.get(configuredBaseDir).toAbsolutePath().normalize();
        this.installHome = examplesDir == null || examplesDir.trim().isEmpty()
                ? null
                : Paths.get(examplesDir).toAbsolutePath().normalize();
    }

    public Path baseDir() {
        return baseDir;
    }

    public Path sessionsDir() {
        return baseDir.resolve("sessions");
    }

    public Path artifactsDir() {
        return baseDir.resolve("artifacts");
    }

    public Path memoriesFile() {
        return baseDir.resolve("memories.json");
    }

    public Path backendDefaultsFile() {
        return baseDir.resolve("backend-defaults.json");
    }

    public Path artifactIndexFile() {
        return artifactsDir().resolve("index.json");
    }

    public Path sessionArtifactDir(String sessionId) {
        return artifactsDir().resolve(sessionId);
    }

    public Path runsDir() {
        return baseDir.resolve("runs");
    }

    public Path sessionRunDir(String sessionId) {
        return runsDir().resolve(sessionId);
    }

    public Path latestRunFile(String sessionId) {
        return sessionRunDir(sessionId).resolve("latest.json");
    }

    /**
     * Returns the examples directory (sibling of {@code bin/} in the install layout),
     * or {@code null} if {@code consilens.examples.dir} system property is not set.
     * Can be overridden at startup via {@code CONSILENS_EXAMPLES_DIR} env var in {@code setenv.sh}.
     */
    public Path examplesDir() {
        return installHome;
    }
}

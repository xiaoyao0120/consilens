package com.consilens.cli.ai.runtime;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Filesystem layout for the CLI-backed AI runtime.
 */
public class AiRuntimePaths {

    private final Path baseDir;

    public AiRuntimePaths() {
        this(System.getenv("CONSILENS_AI_HOME"));
    }

    AiRuntimePaths(String configuredBaseDir) {
        this.baseDir = configuredBaseDir == null || configuredBaseDir.trim().isEmpty()
                ? Paths.get(System.getProperty("user.home"), ".consilens", "ai")
                : Paths.get(configuredBaseDir).toAbsolutePath().normalize();
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
}

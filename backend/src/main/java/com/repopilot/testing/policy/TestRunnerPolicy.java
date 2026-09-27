package com.repopilot.testing.policy;

import com.repopilot.testing.entity.Ecosystem;
import java.time.Duration;

public class TestRunnerPolicy {

    private final Ecosystem ecosystem;
    private final String approvedImage;
    private final String binary;
    private final String workingDirectory;
    private final Duration defaultTimeout;
    private final String memoryLimit;
    private final String cpusLimit;
    private final int pidsLimit;
    private final String tmpfsLimit;
    private final int maxOutputChars;

    public TestRunnerPolicy(Ecosystem ecosystem, String approvedImage, String binary,
                            String workingDirectory, Duration defaultTimeout,
                            String memoryLimit, String cpusLimit, int pidsLimit,
                            String tmpfsLimit, int maxOutputChars) {
        this.ecosystem = ecosystem;
        this.approvedImage = approvedImage;
        this.binary = binary;
        this.workingDirectory = workingDirectory;
        this.defaultTimeout = defaultTimeout;
        this.memoryLimit = memoryLimit;
        this.cpusLimit = cpusLimit;
        this.pidsLimit = pidsLimit;
        this.tmpfsLimit = tmpfsLimit;
        this.maxOutputChars = maxOutputChars;
    }

    public static TestRunnerPolicy forEcosystem(Ecosystem ecosystem) {
        if (ecosystem == null) ecosystem = Ecosystem.PYTHON;
        switch (ecosystem) {
            case MAVEN:
                return new TestRunnerPolicy(Ecosystem.MAVEN, "maven:3.9.6-eclipse-temurin-21-alpine", "mvn",
                        "/workspace/repo", Duration.ofSeconds(60), "512m", "1.5", 100, "512m", 100000);
            case GRADLE:
                return new TestRunnerPolicy(Ecosystem.GRADLE, "gradle:8.6-jdk21-alpine", "gradle",
                        "/workspace/repo", Duration.ofSeconds(60), "512m", "1.5", 100, "512m", 100000);
            case NODE:
                return new TestRunnerPolicy(Ecosystem.NODE, "node:20-slim", "node",
                        "/workspace/repo", Duration.ofSeconds(60), "512m", "1.5", 100, "512m", 100000);
            case GO:
                return new TestRunnerPolicy(Ecosystem.GO, "golang:1.22-alpine", "go",
                        "/workspace/repo", Duration.ofSeconds(60), "512m", "1.5", 100, "512m", 100000);
            case RUST:
                return new TestRunnerPolicy(Ecosystem.RUST, "rust:1.76-alpine", "cargo",
                        "/workspace/repo", Duration.ofSeconds(60), "512m", "1.5", 100, "512m", 100000);
            case PYTHON:
            default:
                return new TestRunnerPolicy(Ecosystem.PYTHON, "python:3.11-slim", "python3",
                        "/workspace/repo", Duration.ofSeconds(60), "512m", "1.5", 100, "512m", 100000);
        }
    }

    public Ecosystem getEcosystem() { return ecosystem; }
    public String getApprovedImage() { return approvedImage; }
    public String getBinary() { return binary; }
    public String getWorkingDirectory() { return workingDirectory; }
    public Duration getDefaultTimeout() { return defaultTimeout; }
    public String getMemoryLimit() { return memoryLimit; }
    public String getCpusLimit() { return cpusLimit; }
    public int getPidsLimit() { return pidsLimit; }
    public String getTmpfsLimit() { return tmpfsLimit; }
    public int getMaxOutputChars() { return maxOutputChars; }
}

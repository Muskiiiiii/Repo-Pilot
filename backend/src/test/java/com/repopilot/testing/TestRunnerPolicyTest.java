package com.repopilot.testing;

import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.policy.TestRunnerPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestRunnerPolicyTest {

    @Test
    @DisplayName("Verify server-side ecosystem policies enforce correct approved images and resource limits")
    void testEcosystemPolicies() {
        TestRunnerPolicy pythonPolicy = TestRunnerPolicy.forEcosystem(Ecosystem.PYTHON);
        assertEquals("python:3.11-slim", pythonPolicy.getApprovedImage());
        assertEquals("python3", pythonPolicy.getBinary());
        assertEquals("512m", pythonPolicy.getMemoryLimit());
        assertEquals("1.5", pythonPolicy.getCpusLimit());
        assertEquals(100, pythonPolicy.getPidsLimit());
        assertEquals(100000, pythonPolicy.getMaxOutputChars());

        TestRunnerPolicy mavenPolicy = TestRunnerPolicy.forEcosystem(Ecosystem.MAVEN);
        assertEquals("maven:3.9.6-eclipse-temurin-21-alpine", mavenPolicy.getApprovedImage());
        assertEquals("mvn", mavenPolicy.getBinary());

        TestRunnerPolicy gradlePolicy = TestRunnerPolicy.forEcosystem(Ecosystem.GRADLE);
        assertEquals("gradle:8.6-jdk21-alpine", gradlePolicy.getApprovedImage());

        TestRunnerPolicy nodePolicy = TestRunnerPolicy.forEcosystem(Ecosystem.NODE);
        assertEquals("node:20-slim", nodePolicy.getApprovedImage());

        TestRunnerPolicy goPolicy = TestRunnerPolicy.forEcosystem(Ecosystem.GO);
        assertEquals("golang:1.22-alpine", goPolicy.getApprovedImage());

        TestRunnerPolicy rustPolicy = TestRunnerPolicy.forEcosystem(Ecosystem.RUST);
        assertEquals("rust:1.76-alpine", rustPolicy.getApprovedImage());
    }
}

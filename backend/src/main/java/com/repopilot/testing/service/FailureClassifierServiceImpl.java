package com.repopilot.testing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.dto.FailureClassification;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.parser.ParsedTestEvidence;
import com.repopilot.testing.parser.TestResultParserCompositeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class FailureClassifierServiceImpl implements FailureClassifierService {

    private static final Logger logger = LoggerFactory.getLogger(FailureClassifierServiceImpl.class);
    private static final String CLASSIFIER_VERSION = "10.3-v1";

    private final TestResultParserCompositeService testResultParserCompositeService;
    private final ObjectMapper objectMapper;

    @Autowired
    public FailureClassifierServiceImpl(TestResultParserCompositeService testResultParserCompositeService,
                                        ObjectMapper objectMapper) {
        this.testResultParserCompositeService = testResultParserCompositeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public FailureClassification classify(Ecosystem ecosystem, DockerExecutionResult execResult, List<CodeChange> codeChanges) {
        if (execResult == null) {
            return new FailureClassification(
                    FailureType.UNKNOWN, Repairability.AMBIGUOUS, ConfidenceLevel.LOW,
                    "Execution result is missing or null", "No execution evidence available",
                    CLASSIFIER_VERSION, Instant.now(), false, Map.of()
            );
        }

        Integer exitCode = execResult.getExitCode();
        String stdout = sanitize(execResult.getStdout());
        String stderr = sanitize(execResult.getStderr());
        String combined = (stdout + "\n" + stderr).toLowerCase();
        FailureType execFailureType = execResult.getFailureType();
        TerminationReason terminationReason = execResult.getTerminationReason();

        boolean related = isFailureRelatedToCodeChanges(stdout, stderr, codeChanges);

        // Precedence 1: CANCELLED
        if (terminationReason == TerminationReason.CANCELLED || execFailureType == FailureType.CANCELLED) {
            return new FailureClassification(
                    FailureType.CANCELLED, Repairability.NOT_REPAIRABLE, ConfidenceLevel.HIGH,
                    "Execution was cancelled by user or system", "Execution cancelled by user or system",
                    CLASSIFIER_VERSION, Instant.now(), related, Map.of("terminationReason", "CANCELLED")
            );
        }

        // Precedence 2: TIMEOUT
        if (terminationReason == TerminationReason.TIMEOUT || execFailureType == FailureType.TIMEOUT || (exitCode != null && exitCode == 124)) {
            return new FailureClassification(
                    FailureType.TIMEOUT, Repairability.NOT_REPAIRABLE, ConfidenceLevel.HIGH,
                    "Execution timed out after exceeding configured limit", "Execution process timed out",
                    CLASSIFIER_VERSION, Instant.now(), related, Map.of("terminationReason", "TIMEOUT", "exitCode", 124)
            );
        }

        // Precedence 3: OOM
        if (terminationReason == TerminationReason.OOM || execFailureType == FailureType.OOM || (exitCode != null && exitCode == 137) ||
            combined.contains("out of memory") || combined.contains("java.lang.outofmemoryerror") || combined.contains("memory limit exceeded")) {
            return new FailureClassification(
                    FailureType.OOM, Repairability.NOT_REPAIRABLE, ConfidenceLevel.HIGH,
                    "Container execution exhausted memory allocation limit", "Container exhausted memory limit (OOM)",
                    CLASSIFIER_VERSION, Instant.now(), related, Map.of("terminationReason", "OOM", "exitCode", exitCode != null ? exitCode : 137)
            );
        }

        // Precedence 4: RESOURCE_LIMIT / PID_LIMIT
        if (terminationReason == TerminationReason.RESOURCE_LIMIT || terminationReason == TerminationReason.PID_LIMIT || execFailureType == FailureType.RESOURCE_LIMIT ||
            combined.contains("pids limit reached") || combined.contains("cannot fork") || combined.contains("resource temporarily unavailable")) {
            return new FailureClassification(
                    FailureType.RESOURCE_LIMIT, Repairability.NOT_REPAIRABLE, ConfidenceLevel.HIGH,
                    "Sandbox container resource ceiling exceeded", "Container resource ceiling reached",
                    CLASSIFIER_VERSION, Instant.now(), related, Map.of("terminationReason", "RESOURCE_LIMIT")
            );
        }

        // Precedence 5: EXECUTION_INFRA_FAILURE
        if (terminationReason == TerminationReason.EXECUTION_INFRA_FAILURE || execFailureType == FailureType.EXECUTION_INFRA_FAILURE ||
            combined.contains("docker daemon unavailable") || combined.contains("failed to launch docker executor process") || combined.contains("container creation failure")) {
            return new FailureClassification(
                    FailureType.EXECUTION_INFRA_FAILURE, Repairability.NOT_REPAIRABLE, ConfidenceLevel.HIGH,
                    "Infrastructure failure encountered during execution", "Sandbox infrastructure error",
                    CLASSIFIER_VERSION, Instant.now(), related, Map.of("terminationReason", "EXECUTION_INFRA_FAILURE")
            );
        }

        // Precedence 6: NETWORK_REQUIRED
        if (combined.contains("urlerror") || combined.contains("cannot assign requested address") || combined.contains("network is unreachable") ||
            combined.contains("could not resolve host") || combined.contains("eai_again") || combined.contains("connectexception") || combined.contains("unknownhostexception")) {
            return new FailureClassification(
                    FailureType.NETWORK_REQUIRED, Repairability.NOT_REPAIRABLE, ConfidenceLevel.HIGH,
                    "Execution requires outbound network access which is disabled by policy", "Outbound network access required but disabled by policy",
                    CLASSIFIER_VERSION, Instant.now(), related, Map.of("networkMode", "none")
            );
        }

        // Precedence 7: DEPENDENCY_UNAVAILABLE
        if (isDependencyFailure(combined)) {
            return new FailureClassification(
                    FailureType.DEPENDENCY_UNAVAILABLE, Repairability.NOT_REPAIRABLE, ConfidenceLevel.HIGH,
                    "External dependency unavailable; application source changes cannot resolve missing dependency", "External dependency or package resolution failed",
                    CLASSIFIER_VERSION, Instant.now(), related, Map.of("ecosystem", ecosystem != null ? ecosystem.name() : "UNKNOWN")
            );
        }

        // Precedence 8: COMPILATION_FAILURE
        if (exitCode != null && exitCode != 0 && isCompilationFailure(ecosystem, combined)) {
            Repairability repairability = related ? Repairability.REPAIRABLE : Repairability.AMBIGUOUS;
            String blockReason = related ? null : "Compilation error is in files unrelated to current CodeChanges";
            return new FailureClassification(
                    FailureType.COMPILATION_FAILURE, repairability, ConfidenceLevel.HIGH,
                    "Compilation error detected in source or test code", blockReason,
                    CLASSIFIER_VERSION, Instant.now(), related, Map.of("ecosystem", ecosystem != null ? ecosystem.name() : "UNKNOWN")
            );
        }

        // Precedence 9: TEST_FAILURE (Parsed test counts or explicit test failure indicators)
        ParsedTestEvidence parsed = testResultParserCompositeService.parse(ecosystem, stdout, stderr);
        if ("PARSED".equalsIgnoreCase(parsed.getParserStatus()) && (parsed.getFailuresCount() > 0 || parsed.getErrorsCount() > 0)) {
            Repairability repairability = related ? Repairability.REPAIRABLE : Repairability.AMBIGUOUS;
            String blockReason = related ? null : "Failure stack trace does not involve modified CodeChanges";
            return new FailureClassification(
                    FailureType.TEST_FAILURE, repairability, ConfidenceLevel.HIGH,
                    "Test failure detected; root cause not yet established.", blockReason,
                    CLASSIFIER_VERSION, Instant.now(), related,
                    Map.of("testsRun", parsed.getTestsRun(), "failuresCount", parsed.getFailuresCount(), "errorsCount", parsed.getErrorsCount(), "passedCount", parsed.getPassedCount())
            );
        }

        if (isTestFailurePattern(combined)) {
            Repairability repairability = related ? Repairability.REPAIRABLE : Repairability.AMBIGUOUS;
            return new FailureClassification(
                    FailureType.TEST_FAILURE, repairability, ConfidenceLevel.MEDIUM,
                    "Test failure detected; root cause not yet established.", null,
                    CLASSIFIER_VERSION, Instant.now(), related, Map.of("evidenceType", "pattern_match")
            );
        }

        // Precedence 10: CODE_FAILURE
        if (exitCode != null && exitCode != 0 && isCodeRuntimeFailurePattern(combined)) {
            Repairability repairability = related ? Repairability.REPAIRABLE : Repairability.AMBIGUOUS;
            return new FailureClassification(
                    FailureType.CODE_FAILURE, repairability, ConfidenceLevel.MEDIUM,
                    "Uncaught runtime code exception detected", null,
                    CLASSIFIER_VERSION, Instant.now(), related, Map.of("exitCode", exitCode)
            );
        }

        // Precedence 11: PASS
        if (exitCode != null && exitCode == 0 && (parsed.getFailuresCount() == 0 && parsed.getErrorsCount() == 0)) {
            return new FailureClassification(
                    FailureType.PASS, Repairability.NOT_REPAIRABLE, ConfidenceLevel.HIGH,
                    "Execution passed cleanly with exit code 0", "Passed cleanly; no repair required",
                    CLASSIFIER_VERSION, Instant.now(), true, Map.of("exitCode", 0)
            );
        }

        // Precedence 12: UNKNOWN / AMBIGUOUS
        return new FailureClassification(
                FailureType.UNKNOWN, Repairability.AMBIGUOUS, ConfidenceLevel.LOW,
                "Evidence insufficient or ambiguous; safe default applied", "Insufficient evidence to determine repairability safely",
                CLASSIFIER_VERSION, Instant.now(), false, Map.of("exitCode", exitCode != null ? exitCode : -1)
        );
    }

    @Override
    public FailureClassification classifyTestRun(TestRun testRun, List<CodeChange> codeChanges) {
        if (testRun == null) {
            return new FailureClassification(
                    FailureType.UNKNOWN, Repairability.AMBIGUOUS, ConfidenceLevel.LOW,
                    "TestRun evidence missing", "No TestRun available",
                    CLASSIFIER_VERSION, Instant.now(), false, Map.of()
            );
        }

        DockerExecutionResult result = new DockerExecutionResult(
                testRun.getExitCode(), testRun.getStdoutSummary(), testRun.getStderrSummary(),
                testRun.getDurationMs(), testRun.getContainerId(), testRun.getFailureType(),
                Boolean.TRUE.equals(testRun.getStdoutTruncated()), Boolean.TRUE.equals(testRun.getStderrTruncated()),
                testRun.getCommand(), testRun.getTerminationReason(),
                testRun.getStdoutBytesObserved() != null ? testRun.getStdoutBytesObserved() : 0L,
                testRun.getStderrBytesObserved() != null ? testRun.getStderrBytesObserved() : 0L
        );

        return classify(testRun.getEcosystem(), result, codeChanges);
    }

    @Override
    public boolean isFailureRelatedToCodeChanges(String stdout, String stderr, List<CodeChange> codeChanges) {
        if (codeChanges == null || codeChanges.isEmpty()) {
            return true; // Default to true if snapshot context was not captured
        }

        String combined = (stdout + "\n" + stderr).toLowerCase();

        Set<String> modifiedNames = new HashSet<>();
        for (CodeChange cc : codeChanges) {
            if (cc.getFilePath() != null && !cc.getFilePath().isBlank()) {
                String path = cc.getFilePath().replace("\\", "/");
                int lastSlash = path.lastIndexOf("/");
                String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
                modifiedNames.add(filename.toLowerCase());
                if (filename.contains(".")) {
                    modifiedNames.add(filename.substring(0, filename.lastIndexOf(".")).toLowerCase());
                }
            }
        }

        for (String name : modifiedNames) {
            if (combined.contains(name)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String sanitize(String content) {
        if (content == null || content.isBlank()) return content;
        String sanitized = content;
        // AWS Access Keys
        sanitized = sanitized.replaceAll("AKIA[0-9A-Z]{16}", "[REDACTED_AWS_KEY]");
        // GitHub PAT
        sanitized = sanitized.replaceAll("gh[pousr]_[a-zA-Z0-9]{30,40}", "[REDACTED_GITHUB_TOKEN]");
        sanitized = sanitized.replaceAll("github_pat_[a-zA-Z0-9_]{30,90}", "[REDACTED_GITHUB_TOKEN]");
        // OpenAI / LLM API Keys
        sanitized = sanitized.replaceAll("sk-[a-zA-Z0-9_-]{20,}", "[REDACTED_LLM_KEY]");
        // Voyage AI Tokens
        sanitized = sanitized.replaceAll("vy-[a-zA-Z0-9_-]{20,}", "[REDACTED_VOYAGE_KEY]");
        // DB Connection URLs
        sanitized = sanitized.replaceAll("(postgres|postgresql|mysql|mongodb|redis)://[^\\s\"']+", "$1://[REDACTED_DB_CREDENTIALS]");
        // Private Keys
        sanitized = sanitized.replaceAll("-----BEGIN (?:RSA |EC |PGP |OPENSSH )?PRIVATE KEY-----", "[REDACTED_PRIVATE_KEY]");
        // Generic secrets
        sanitized = sanitized.replaceAll("(?i)(api[_-]?key|secret[_-]?key|password|token)\\s*[:=]\\s*[\"'][^\"']{8,}[\"']", "$1=\"[REDACTED_SECRET]\"");
        return sanitized;
    }

    private boolean isDependencyFailure(String combined) {
        return combined.contains("could not resolve dependencies") ||
               combined.contains("could not find artifact") ||
               combined.contains("transfer failed for") ||
               combined.contains("could not resolve all files") ||
               combined.contains("err_module_not_found") ||
               combined.contains("npm err! code e404") ||
               combined.contains("modulenotfounderror") ||
               combined.contains("no matching distribution found") ||
               combined.contains("failed to download") ||
               combined.contains("failed to fetch") ||
               combined.contains("module lookup failed") ||
               combined.contains("module download failed");
    }

    private boolean isCompilationFailure(Ecosystem ecosystem, String combined) {
        if (combined.contains("[error] compilation error") ||
            combined.contains("cannot find symbol") ||
            combined.contains("package does not exist") ||
            combined.contains("incompatible types") ||
            combined.contains("compile failed")) {
            return true;
        }
        if (ecosystem == Ecosystem.PYTHON && combined.contains("syntaxerror")) {
            return true;
        }
        if (ecosystem == Ecosystem.NODE && (combined.contains("syntaxerror") || combined.contains("unexpected token"))) {
            return true;
        }
        if (ecosystem == Ecosystem.GO && (combined.contains("undefined:") || combined.contains("cannot use ") || combined.contains("syntax error"))) {
            return true;
        }
        if (ecosystem == Ecosystem.RUST && (combined.contains("error[e") || combined.contains("mismatched types") || combined.contains("expected type"))) {
            return true;
        }
        return false;
    }

    private boolean isTestFailurePattern(String combined) {
        return combined.contains("assertionerror") ||
               combined.contains("fail: test_") ||
               combined.contains("--- fail:") ||
               combined.contains("test result: failed") ||
               combined.contains("failures:");
    }

    private boolean isCodeRuntimeFailurePattern(String combined) {
        return combined.contains("nullpointerexception") ||
               combined.contains("indexoutofboundsexception") ||
               combined.contains("zerodivisionerror") ||
               combined.contains("traceback (most recent call last)") ||
               combined.contains("uncaught exception");
    }
}

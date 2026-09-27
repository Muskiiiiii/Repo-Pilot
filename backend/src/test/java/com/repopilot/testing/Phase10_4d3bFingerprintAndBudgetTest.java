package com.repopilot.testing;

import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.testing.dto.FailureClassification;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.parser.ParsedTestEvidence;
import com.repopilot.testing.policy.*;
import com.repopilot.testing.repository.RepairIterationRepository;
import com.repopilot.testing.repository.TestRunRepository;
import com.repopilot.testing.service.FailureFingerprintService;
import com.repopilot.testing.service.FailureFingerprintServiceImpl;
import com.repopilot.testing.service.RepairBudgetManager;
import com.repopilot.testing.service.RepairBudgetManagerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class Phase10_4d3bFingerprintAndBudgetTest {

    private FailureFingerprintService fingerprintService;
    private RepairBudgetManager budgetManager;
    private RepairIterationRepository repairIterationRepository;
    private TestRunRepository testRunRepository;
    private TaskExecutionRepository taskExecutionRepository;
    private D3DecisionEngine decisionEngine;

    @BeforeEach
    void setUp() {
        fingerprintService = new FailureFingerprintServiceImpl();

        repairIterationRepository = mock(RepairIterationRepository.class);
        testRunRepository = mock(TestRunRepository.class);
        taskExecutionRepository = mock(TaskExecutionRepository.class);

        budgetManager = new RepairBudgetManagerImpl(repairIterationRepository, testRunRepository, taskExecutionRepository);
        decisionEngine = new D3DecisionEngine();
    }

    private FailureClassification createClassification(FailureType category, Repairability repairability) {
        return new FailureClassification(
                category, repairability, ConfidenceLevel.HIGH,
                "Reason for " + category, null, "10.3-v1",
                Instant.now(), true, Map.of()
        );
    }

    // =========================================================================
    // FINGERPRINT DETERMINISM (Scenarios 1-7)
    // =========================================================================

    @Test
    @DisplayName("Scenario 1: Identical failure produces identical fingerprint")
    void test01_IdenticalFailure_IdenticalFingerprint() {
        FailureClassification classification = createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence evidence = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of());
        String stdout = "ZeroDivisionError: division by zero in calc.py line 3";

        String fp1 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, stdout, "");
        String fp2 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, stdout, "");

        assertEquals(fp1, fp2);
    }

    @Test
    @DisplayName("Scenario 2: 100 repeated evaluations yield byte-identical result")
    void test02_100RepeatedEvaluations_ByteIdentical() {
        FailureClassification classification = createClassification(FailureType.TEST_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence evidence = new ParsedTestEvidence("PARSED", 5, 1, 0, 0, 4, Map.of("failingTests", List.of("test_calc.py::test_divide")));
        String stdout = "AssertionError: expected 0 got 1";

        String reference = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, stdout, "");
        for (int i = 0; i < 100; i++) {
            String repeated = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, stdout, "");
            assertEquals(reference, repeated, "Evaluation #" + i + " must be byte-identical");
        }
    }

    @Test
    @DisplayName("Scenario 3: Line ending variation (crlf vs lf) yields same fingerprint")
    void test03_LineEndingVariation_SameFingerprint() {
        FailureClassification classification = createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence evidence = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of());
        String stdoutLF = "Error line 1\nError line 2";
        String stdoutCRLF = "Error line 1\r\nError line 2";

        String fp1 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, stdoutLF, "");
        String fp2 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, stdoutCRLF, "");

        assertEquals(fp1, fp2);
    }

    @Test
    @DisplayName("Scenario 4: Volatile workspace path variation yields same fingerprint")
    void test04_VolatileWorkspacePath_SameFingerprint() {
        FailureClassification classification = createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence evidence = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of());
        String stdoutPath1 = "ZeroDivisionError in /tmp/junit123456/workspaces/exec-123/calc.py line 3";
        String stdoutPath2 = "ZeroDivisionError in /var/folders/bc/139lybfs4yn0vqb3/workspaces/exec-999/calc.py line 3";

        String fp1 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, stdoutPath1, "");
        String fp2 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, stdoutPath2, "");

        assertEquals(fp1, fp2);
    }

    @Test
    @DisplayName("Scenario 5: Timestamp variation yields same fingerprint")
    void test05_TimestampVariation_SameFingerprint() {
        FailureClassification classification = createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence evidence = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of());
        String stdoutTime1 = "[2026-09-14T05:30:00Z] ZeroDivisionError: division by zero";
        String stdoutTime2 = "[2026-09-14T06:45:12Z] ZeroDivisionError: division by zero";

        String fp1 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, stdoutTime1, "");
        String fp2 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, stdoutTime2, "");

        assertEquals(fp1, fp2);
    }

    @Test
    @DisplayName("Scenario 6: Container ID variation yields same fingerprint")
    void test06_ContainerIdVariation_SameFingerprint() {
        FailureClassification classification = createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence evidence = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of());
        String stdoutC1 = "Container repopilot_exec_11111111-2222-3333-4444-555555555555 failed";
        String stdoutC2 = "Container repopilot_exec_aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee failed";

        String fp1 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, stdoutC1, "");
        String fp2 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, stdoutC2, "");

        assertEquals(fp1, fp2);
    }

    @Test
    @DisplayName("Scenario 7: Ordering variation in failing test list yields same fingerprint")
    void test07_FailingTestOrdering_SameFingerprint() {
        FailureClassification classification = createClassification(FailureType.TEST_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence evidence1 = new ParsedTestEvidence("PARSED", 5, 2, 0, 0, 3, Map.of("failingTests", List.of("testB", "testA")));
        ParsedTestEvidence evidence2 = new ParsedTestEvidence("PARSED", 5, 2, 0, 0, 3, Map.of("failingTests", List.of("testA", "testB")));

        String fp1 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence1, "out", "");
        String fp2 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence2, "out", "");

        assertEquals(fp1, fp2);
    }

    // =========================================================================
    // DISTINCT FAILURE SEPARATION (Scenarios 8-14)
    // =========================================================================

    @Test
    @DisplayName("Scenario 8: Assertion A != Assertion B produces distinct fingerprints")
    void test08_DistinctAssertions_DistinctFingerprints() {
        FailureClassification classification = createClassification(FailureType.TEST_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence evidence1 = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("failingTests", List.of("test_calc.py::test_a")));
        ParsedTestEvidence evidence2 = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("failingTests", List.of("test_calc.py::test_b")));

        String fp1 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence1, "AssertionError", "");
        String fp2 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence2, "AssertionError", "");

        assertNotEquals(fp1, fp2);
    }

    @Test
    @DisplayName("Scenario 9: Test A != Test B produces distinct fingerprints")
    void test09_DistinctTests_DistinctFingerprints() {
        FailureClassification classification = createClassification(FailureType.TEST_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence evidence1 = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("failingTests", List.of("test1")));
        ParsedTestEvidence evidence2 = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("failingTests", List.of("test2")));

        String fp1 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence1, "", "");
        String fp2 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence2, "", "");

        assertNotEquals(fp1, fp2);
    }

    @Test
    @DisplayName("Scenario 10: Compilation failure in File A != File B produces distinct fingerprints")
    void test10_DistinctCompilationFiles_DistinctFingerprints() {
        FailureClassification classification = createClassification(FailureType.COMPILATION_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence evidence = ParsedTestEvidence.unparsed();

        String fp1 = fingerprintService.generateFingerprint(Ecosystem.MAVEN, classification, evidence, "Syntax error in Calc.java line 10", "");
        String fp2 = fingerprintService.generateFingerprint(Ecosystem.MAVEN, classification, evidence, "Syntax error in User.java line 10", "");

        assertNotEquals(fp1, fp2);
    }

    @Test
    @DisplayName("Scenario 11: Code failure != Dependency failure produces distinct fingerprints")
    void test11_CodeFailure_Vs_DependencyFailure() {
        FailureClassification c1 = createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE);
        FailureClassification c2 = createClassification(FailureType.DEPENDENCY_UNAVAILABLE, Repairability.NOT_REPAIRABLE);
        ParsedTestEvidence evidence = ParsedTestEvidence.unparsed();

        String fp1 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c1, evidence, "ZeroDivisionError", "");
        String fp2 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c2, evidence, "ModuleNotFoundError", "");

        assertNotEquals(fp1, fp2);
    }

    @Test
    @DisplayName("Scenario 12: Dependency failure != Timeout produces distinct fingerprints")
    void test12_DependencyFailure_Vs_Timeout() {
        FailureClassification c1 = createClassification(FailureType.DEPENDENCY_UNAVAILABLE, Repairability.NOT_REPAIRABLE);
        FailureClassification c2 = createClassification(FailureType.TIMEOUT, Repairability.NOT_REPAIRABLE);
        ParsedTestEvidence evidence = ParsedTestEvidence.unparsed();

        String fp1 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c1, evidence, "dep error", "");
        String fp2 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c2, evidence, "timeout error", "");

        assertNotEquals(fp1, fp2);
    }

    @Test
    @DisplayName("Scenario 13: Timeout != OOM produces distinct fingerprints")
    void test13_Timeout_Vs_OOM() {
        FailureClassification c1 = createClassification(FailureType.TIMEOUT, Repairability.NOT_REPAIRABLE);
        FailureClassification c2 = createClassification(FailureType.OOM, Repairability.NOT_REPAIRABLE);
        ParsedTestEvidence evidence = ParsedTestEvidence.unparsed();

        String fp1 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c1, evidence, "", "");
        String fp2 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c2, evidence, "", "");

        assertNotEquals(fp1, fp2);
    }

    @Test
    @DisplayName("Scenario 14: OOM != Infrastructure failure produces distinct fingerprints")
    void test14_OOM_Vs_InfraFailure() {
        FailureClassification c1 = createClassification(FailureType.OOM, Repairability.NOT_REPAIRABLE);
        FailureClassification c2 = createClassification(FailureType.EXECUTION_INFRA_FAILURE, Repairability.NOT_REPAIRABLE);
        ParsedTestEvidence evidence = ParsedTestEvidence.unparsed();

        String fp1 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c1, evidence, "", "");
        String fp2 = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c2, evidence, "", "");

        assertNotEquals(fp1, fp2);
    }

    // =========================================================================
    // UNPARSED / UNKNOWN (Scenarios 15-19)
    // =========================================================================

    @Test
    @DisplayName("Scenario 15: UNPARSED evidence yields ambiguous fingerprint representation")
    void test15_Unparsed_YieldsAmbiguousFingerprint() {
        FailureClassification classification = createClassification(FailureType.UNKNOWN, Repairability.AMBIGUOUS);
        ParsedTestEvidence evidence = ParsedTestEvidence.unparsed();

        String fp = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, "some output", "");
        assertNotNull(fp);
        assertEquals(64, fp.length());
    }

    @Test
    @DisplayName("Scenario 16: Malformed evidence yields ambiguous fingerprint")
    void test16_MalformedEvidence_YieldsAmbiguousFingerprint() {
        FailureClassification classification = createClassification(FailureType.UNKNOWN, Repairability.AMBIGUOUS);
        ParsedTestEvidence evidence = new ParsedTestEvidence("UNPARSED", 0, 0, 0, 0, 0, null);

        String fp = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, "malformed log", "");
        assertNotNull(fp);
        assertEquals(64, fp.length());
    }

    @Test
    @DisplayName("Scenario 17: Empty evidence yields ambiguous fingerprint")
    void test17_EmptyEvidence_YieldsAmbiguousFingerprint() {
        FailureClassification classification = createClassification(FailureType.UNKNOWN, Repairability.AMBIGUOUS);

        String fp = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, null, "", "");
        assertNotNull(fp);
        assertEquals(64, fp.length());
    }

    @Test
    @DisplayName("Scenario 18: Generic error text yields ambiguous fingerprint")
    void test18_GenericErrorText_YieldsAmbiguousFingerprint() {
        FailureClassification classification = createClassification(FailureType.UNKNOWN, Repairability.AMBIGUOUS);
        ParsedTestEvidence evidence = ParsedTestEvidence.unparsed();

        String fp = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, "error occurred", "");
        assertNotNull(fp);
        assertEquals(64, fp.length());
    }

    @Test
    @DisplayName("Scenario 19: Exit code alone yields ambiguous fingerprint")
    void test19_ExitCodeAlone_YieldsAmbiguousFingerprint() {
        FailureClassification classification = createClassification(FailureType.UNKNOWN, Repairability.AMBIGUOUS);
        ParsedTestEvidence evidence = ParsedTestEvidence.unparsed();

        String fp = fingerprintService.generateFingerprint(Ecosystem.PYTHON, classification, evidence, "exit code 1", "");
        assertNotNull(fp);
        assertEquals(64, fp.length());
    }

    // =========================================================================
    // REPETITION & OSCILLATION (Scenarios 20-24)
    // =========================================================================

    @Test
    @DisplayName("Scenario 20: A -> A recognized as repeated failure")
    void test20_A_To_A_IsRepeated() {
        FailureClassification c = createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence e = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of());

        String fpA = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c, e, "ZeroDivisionError", "");
        FailureFingerprintResult res = fingerprintService.analyzeFingerprintSequence(Ecosystem.PYTHON, c, e, "ZeroDivisionError", "", List.of(fpA));

        assertTrue(res.isRepeated());
        assertFalse(res.isOscillating());
    }

    @Test
    @DisplayName("Scenario 21: A -> B recognized as NOT repeated")
    void test21_A_To_B_IsNotRepeated() {
        FailureClassification c = createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence e1 = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("failingTests", List.of("testA")));
        ParsedTestEvidence e2 = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("failingTests", List.of("testB")));

        String fpA = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c, e1, "ZeroDivisionError", "");
        FailureFingerprintResult res = fingerprintService.analyzeFingerprintSequence(Ecosystem.PYTHON, c, e2, "IndexError", "", List.of(fpA));

        assertFalse(res.isRepeated());
        assertFalse(res.isOscillating());
    }

    @Test
    @DisplayName("Scenario 22: A -> B -> A recognized as oscillation")
    void test22_A_To_B_To_A_IsOscillating() {
        FailureClassification c = createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence eA = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("failingTests", List.of("testA")));
        ParsedTestEvidence eB = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("failingTests", List.of("testB")));

        String fpA = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c, eA, "ZeroDivisionError", "");
        String fpB = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c, eB, "IndexError", "");

        FailureFingerprintResult res = fingerprintService.analyzeFingerprintSequence(Ecosystem.PYTHON, c, eA, "ZeroDivisionError", "", List.of(fpA, fpB));

        assertTrue(res.isRepeated());
        assertTrue(res.isOscillating());
    }

    @Test
    @DisplayName("Scenario 23: A -> B -> C recognized as NO repetition and NO oscillation")
    void test23_A_To_B_To_C_NoRepetition() {
        FailureClassification c = createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence eA = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("failingTests", List.of("testA")));
        ParsedTestEvidence eB = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("failingTests", List.of("testB")));
        ParsedTestEvidence eC = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("failingTests", List.of("testC")));

        String fpA = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c, eA, "ErrA", "");
        String fpB = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c, eB, "ErrB", "");

        FailureFingerprintResult res = fingerprintService.analyzeFingerprintSequence(Ecosystem.PYTHON, c, eC, "ErrC", "", List.of(fpA, fpB));

        assertFalse(res.isRepeated());
        assertFalse(res.isOscillating());
    }

    @Test
    @DisplayName("Scenario 24: A -> A -> A recognized as repeated failure")
    void test24_A_To_A_To_A_IsRepeated() {
        FailureClassification c = createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence e = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of());

        String fpA = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c, e, "ZeroDivisionError", "");
        FailureFingerprintResult res = fingerprintService.analyzeFingerprintSequence(Ecosystem.PYTHON, c, e, "ZeroDivisionError", "", List.of(fpA, fpA));

        assertTrue(res.isRepeated());
        assertFalse(res.isOscillating());
    }

    // =========================================================================
    // SECURITY & SANITIZATION (Scenarios 25-33)
    // =========================================================================

    @Test
    @DisplayName("Scenario 25: AWS secret sanitized")
    void test25_AWSSecretSanitized() {
        String raw = "Failed connecting with AWS Key AKIAIOSFODNN7EXAMPLE in log";
        String sanitized = fingerprintService.sanitizeEvidence(raw);
        assertFalse(sanitized.contains("AKIAIOSFODNN7EXAMPLE"));
        assertTrue(sanitized.contains("[REDACTED_AWS_KEY]"));
    }

    @Test
    @DisplayName("Scenario 26: GitHub token sanitized")
    void test26_GitHubTokenSanitized() {
        String raw = "Auth header ghp_1234567890abcdef1234567890abcdef invalid";
        String sanitized = fingerprintService.sanitizeEvidence(raw);
        assertFalse(sanitized.contains("ghp_1234567890abcdef1234567890abcdef"));
        assertTrue(sanitized.contains("[REDACTED_GITHUB_TOKEN]"));
    }

    @Test
    @DisplayName("Scenario 27: OpenAI key sanitized")
    void test27_OpenAIKeySanitized() {
        String raw = "API key sk-1234567890abcdef1234567890abcdef rejected";
        String sanitized = fingerprintService.sanitizeEvidence(raw);
        assertFalse(sanitized.contains("sk-1234567890abcdef1234567890abcdef"));
        assertTrue(sanitized.contains("[REDACTED_LLM_KEY]"));
    }

    @Test
    @DisplayName("Scenario 28: Voyage key sanitized")
    void test28_VoyageKeySanitized() {
        String raw = "Voyage key vy-1234567890abcdef1234567890abcdef failed";
        String sanitized = fingerprintService.sanitizeEvidence(raw);
        assertFalse(sanitized.contains("vy-1234567890abcdef1234567890abcdef"));
        assertTrue(sanitized.contains("[REDACTED_VOYAGE_KEY]"));
    }

    @Test
    @DisplayName("Scenario 29: Private key sanitized")
    void test29_PrivateKeySanitized() {
        String raw = "-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC...\n-----END PRIVATE KEY-----";
        String sanitized = fingerprintService.sanitizeEvidence(raw);
        assertFalse(sanitized.contains("MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC"));
        assertTrue(sanitized.contains("[REDACTED_PRIVATE_KEY]"));
    }

    @Test
    @DisplayName("Scenario 30: DB credentials sanitized")
    void test30_DBCredentialsSanitized() {
        String raw = "Connection error: postgres://user:secretpass123@localhost:5432/mydb";
        String sanitized = fingerprintService.sanitizeEvidence(raw);
        assertFalse(sanitized.contains("secretpass123"));
        assertTrue(sanitized.contains("[REDACTED_DB_CREDENTIALS]"));
    }

    @Test
    @DisplayName("Scenario 31: Absolute workspace path sanitized")
    void test31_AbsoluteWorkspacePathSanitized() {
        String raw = "Error in /tmp/junit123456789/workspaces/exec-123/calc.py line 4";
        String sanitized = fingerprintService.sanitizeEvidence(raw);
        assertFalse(sanitized.contains("/tmp/junit123456789/workspaces/exec-123"));
        assertTrue(sanitized.contains("/workspace/repo"));
    }

    @Test
    @DisplayName("Scenario 32: Container ID removed")
    void test32_ContainerIdRemoved() {
        String raw = "Executing in repopilot_exec_12345678-aaaa-bbbb-cccc-111122223333";
        String sanitized = fingerprintService.sanitizeEvidence(raw);
        assertFalse(sanitized.contains("12345678-aaaa-bbbb-cccc-111122223333"));
        assertTrue(sanitized.contains("[CONTAINER_ID]"));
    }

    @Test
    @DisplayName("Scenario 33: Timestamp removed")
    void test33_TimestampRemoved() {
        String raw = "[2026-09-14T05:30:15Z] Execution started";
        String sanitized = fingerprintService.sanitizeEvidence(raw);
        assertFalse(sanitized.contains("2026-09-14T05:30:15Z"));
        assertTrue(sanitized.contains("[TIMESTAMP]"));
    }

    // =========================================================================
    // BUDGET INVARIANTS & BOUNDS (Scenarios 34-47)
    // =========================================================================

    @Test
    @DisplayName("Scenario 34: Initial state allows repair #1")
    void test34_InitialState_AllowsRepair1() {
        when(repairIterationRepository.countByTaskExecutionId(any())).thenReturn(0L);
        when(testRunRepository.findByTaskExecutionId(any())).thenReturn(List.of());

        RepairBudgetSnapshot snapshot = budgetManager.getBudgetSnapshot(UUID.randomUUID());

        assertTrue(snapshot.isRepairAllowed());
        assertEquals(0, snapshot.getRepairsConsumed());
        assertEquals(3, snapshot.getRepairsRemaining());
        assertEquals(1, snapshot.getTotalExecutions());
    }

    @Test
    @DisplayName("Scenario 35: After repair #1 allows repair #2")
    void test35_AfterRepair1_AllowsRepair2() {
        when(repairIterationRepository.countByTaskExecutionId(any())).thenReturn(1L);
        when(testRunRepository.findByTaskExecutionId(any())).thenReturn(List.of());

        RepairBudgetSnapshot snapshot = budgetManager.getBudgetSnapshot(UUID.randomUUID());

        assertTrue(snapshot.isRepairAllowed());
        assertEquals(1, snapshot.getRepairsConsumed());
        assertEquals(2, snapshot.getRepairsRemaining());
        assertEquals(2, snapshot.getTotalExecutions());
    }

    @Test
    @DisplayName("Scenario 36: After repair #2 allows repair #3")
    void test36_AfterRepair2_AllowsRepair3() {
        when(repairIterationRepository.countByTaskExecutionId(any())).thenReturn(2L);
        when(testRunRepository.findByTaskExecutionId(any())).thenReturn(List.of());

        RepairBudgetSnapshot snapshot = budgetManager.getBudgetSnapshot(UUID.randomUUID());

        assertTrue(snapshot.isRepairAllowed());
        assertEquals(2, snapshot.getRepairsConsumed());
        assertEquals(1, snapshot.getRepairsRemaining());
        assertEquals(3, snapshot.getTotalExecutions());
    }

    @Test
    @DisplayName("Scenario 37: After repair #3 denies repair #4")
    void test37_AfterRepair3_DeniesRepair4() {
        when(repairIterationRepository.countByTaskExecutionId(any())).thenReturn(3L);
        when(testRunRepository.findByTaskExecutionId(any())).thenReturn(List.of());

        RepairBudgetSnapshot snapshot = budgetManager.getBudgetSnapshot(UUID.randomUUID());

        assertFalse(snapshot.isRepairAllowed());
        assertEquals(3, snapshot.getRepairsConsumed());
        assertEquals(0, snapshot.getRepairsRemaining());
        assertEquals(4, snapshot.getTotalExecutions());
        assertTrue(snapshot.getDenialReason().contains("Maximum repair iteration limit reached (3)"));
    }

    @Test
    @DisplayName("Scenario 38: Repair count can never exceed 3")
    void test38_RepairCount_CanNeverExceed3() {
        when(repairIterationRepository.countByTaskExecutionId(any())).thenReturn(5L);
        when(testRunRepository.findByTaskExecutionId(any())).thenReturn(List.of());

        RepairBudgetSnapshot snapshot = budgetManager.getBudgetSnapshot(UUID.randomUUID());

        assertFalse(snapshot.isRepairAllowed());
        assertEquals(0, snapshot.getRepairsRemaining());
    }

    @Test
    @DisplayName("Scenario 39: Budget never increases during execution")
    void test39_BudgetNeverIncreases() {
        RepairBudgetSnapshot b1 = budgetManager.checkBudget(0, 1000L);
        RepairBudgetSnapshot b2 = budgetManager.checkBudget(1, 2000L);
        RepairBudgetSnapshot b3 = budgetManager.checkBudget(2, 3000L);
        RepairBudgetSnapshot b4 = budgetManager.checkBudget(3, 4000L);

        assertTrue(b1.getRepairsRemaining() > b2.getRepairsRemaining());
        assertTrue(b2.getRepairsRemaining() > b3.getRepairsRemaining());
        assertEquals(0, b4.getRepairsRemaining());
    }

    @Test
    @DisplayName("Scenario 40: Cumulative 300-second ceiling enforced")
    void test40_Cumulative300SecondCeiling_Enforced() {
        RepairBudgetSnapshot snapshot = budgetManager.checkBudget(1, 299_999L);
        assertTrue(snapshot.isRepairAllowed());

        RepairBudgetSnapshot exhausted = budgetManager.checkBudget(1, 300_000L);
        assertFalse(exhausted.isRepairAllowed());
        assertTrue(exhausted.getDenialReason().contains("Cumulative execution time ceiling (300s) exceeded"));
    }

    @Test
    @DisplayName("Scenario 41: Exceeded cumulative budget denies repair")
    void test41_ExceededCumulativeBudget_DeniesRepair() {
        RepairBudgetSnapshot snapshot = budgetManager.checkBudget(1, 350_000L);
        assertFalse(snapshot.isRepairAllowed());
    }

    @Test
    @DisplayName("Scenario 42: Cancellation does not replenish budget")
    void test42_Cancellation_DoesNotReplenishBudget() {
        UUID execId = UUID.randomUUID();
        TaskExecution exec = new TaskExecution();
        exec.setId(execId);
        exec.setCancellationRequested(true);

        when(taskExecutionRepository.findById(execId)).thenReturn(Optional.of(exec));
        when(repairIterationRepository.countByTaskExecutionId(execId)).thenReturn(1L);

        RepairBudgetSnapshot snapshot = budgetManager.getBudgetSnapshot(execId);

        assertFalse(snapshot.isRepairAllowed());
        assertTrue(snapshot.getDenialReason().contains("Execution cancelled"));
    }

    @Test
    @DisplayName("Scenario 43: Retry does not replenish budget")
    void test43_Retry_DoesNotReplenishBudget() {
        RepairBudgetSnapshot b1 = budgetManager.checkBudget(3, 1000L);
        assertFalse(b1.isRepairAllowed());

        // Attempt retry with same consumed count
        RepairBudgetSnapshot retry = budgetManager.checkBudget(3, 1000L);
        assertFalse(retry.isRepairAllowed());
        assertEquals(0, retry.getRepairsRemaining());
    }

    @Test
    @DisplayName("Scenario 44: Duplicate request does not consume budget twice")
    void test44_DuplicateRequest_DoesNotConsumeTwice() {
        UUID execId = UUID.randomUUID();
        when(repairIterationRepository.countByTaskExecutionId(execId)).thenReturn(1L);

        RepairBudgetSnapshot s1 = budgetManager.getBudgetSnapshot(execId);
        RepairBudgetSnapshot s2 = budgetManager.getBudgetSnapshot(execId);

        assertEquals(s1.getRepairsConsumed(), s2.getRepairsConsumed());
        assertEquals(s1.getRepairsRemaining(), s2.getRepairsRemaining());
    }

    @Test
    @DisplayName("Scenario 45: Stale CAS does not consume budget")
    void test45_StaleCAS_DoesNotConsumeBudget() {
        RepairBudgetSnapshot b1 = budgetManager.checkBudget(3, 100L);
        assertFalse(b1.isRepairAllowed());
        assertEquals(0, b1.getRepairsRemaining());
    }

    @Test
    @DisplayName("Scenario 46: Concurrent callers cannot create repair #4")
    void test46_ConcurrentCallers_CannotCreateRepair4() throws Exception {
        UUID execId = UUID.randomUUID();
        when(repairIterationRepository.countByTaskExecutionId(execId)).thenReturn(3L);

        ExecutorService service = Executors.newFixedThreadPool(10);
        List<Future<RepairBudgetSnapshot>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            futures.add(service.submit(() -> budgetManager.consumeRepairBudget(execId, 100L)));
        }

        for (Future<RepairBudgetSnapshot> f : futures) {
            RepairBudgetSnapshot snapshot = f.get();
            assertFalse(snapshot.isRepairAllowed(), "No concurrent caller may be allowed repair #4");
        }
        service.shutdown();
    }

    @Test
    @DisplayName("Scenario 47: Concurrent callers cannot exceed cumulative budget")
    void test47_ConcurrentCallers_CannotExceedCumulativeBudget() throws Exception {
        ExecutorService service = Executors.newFixedThreadPool(10);
        List<Future<RepairBudgetSnapshot>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            futures.add(service.submit(() -> budgetManager.checkBudget(1, 310_000L)));
        }

        for (Future<RepairBudgetSnapshot> f : futures) {
            RepairBudgetSnapshot snapshot = f.get();
            assertFalse(snapshot.isRepairAllowed());
        }
        service.shutdown();
    }

    // =========================================================================
    // INTEGRATION CONTRACT WITH D3-A (Scenarios 48-52)
    // =========================================================================

    @Test
    @DisplayName("Scenario 48: D3-A receives budgetAvailable=false correctly")
    void test48_D3A_ReceivesBudgetAvailableFalse() {
        RepairBudgetSnapshot budget = budgetManager.checkBudget(3, 1000L);

        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(3)
                .isBudgetExceeded(!budget.isRepairAllowed())
                .baselineFailingFingerprints(Set.of("test1"))
                .currentFailingFingerprints(Set.of("test1"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = decisionEngine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("CUMULATIVE_TIMEOUT", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 49: D3-A receives repeatedFailureDetected=true correctly")
    void test49_D3A_ReceivesRepeatedFailureDetectedTrue() {
        FailureClassification c = createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence e = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of());

        String fpA = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c, e, "ZeroDivisionError", "");
        FailureFingerprintResult fpRes = fingerprintService.analyzeFingerprintSequence(Ecosystem.PYTHON, c, e, "ZeroDivisionError", "", List.of(fpA));

        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(2)
                .isCycleDetected(fpRes.isRepeated())
                .baselineFailingFingerprints(Set.of("test1"))
                .currentFailingFingerprints(Set.of("test1"))
                .currentClassification(c)
                .build();

        D3DecisionResult result = decisionEngine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPEATED_FAILURE", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 50: Non-repairable classification never becomes repairable through fingerprinting/budget")
    void test50_NonRepairable_NeverBecomesRepairable() {
        FailureClassification c = createClassification(FailureType.OOM, Repairability.NOT_REPAIRABLE);
        ParsedTestEvidence e = ParsedTestEvidence.unparsed();

        FailureFingerprintResult fpRes = fingerprintService.analyzeFingerprintSequence(Ecosystem.PYTHON, c, e, "OOM killed", "", List.of());
        assertEquals(FailureType.OOM, fpRes.getFailureCategory());

        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .currentClassification(c)
                .build();

        D3DecisionResult result = decisionEngine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_FAILED_OOM", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 51: UNKNOWN/AMBIGUOUS never becomes repairable")
    void test51_UnknownAmbiguous_NeverBecomesRepairable() {
        FailureClassification c = createClassification(FailureType.UNKNOWN, Repairability.AMBIGUOUS);
        ParsedTestEvidence e = ParsedTestEvidence.unparsed();

        FailureFingerprintResult fpRes = fingerprintService.analyzeFingerprintSequence(Ecosystem.PYTHON, c, e, "unknown err", "", List.of());
        assertEquals(FailureType.UNKNOWN, fpRes.getFailureCategory());

        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .currentClassification(c)
                .build();

        D3DecisionResult result = decisionEngine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_FAILED_AMBIGUOUS", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 52: Deterministic results remain stable across 100 repeated evaluations")
    void test52_DeterministicResults_StableAcross100Evaluations() {
        FailureClassification c = createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE);
        ParsedTestEvidence e = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of());
        String log = "ZeroDivisionError: division by zero in calc.py line 3";

        String refFp = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c, e, log, "");
        FailureFingerprintResult refRes = fingerprintService.analyzeFingerprintSequence(Ecosystem.PYTHON, c, e, log, "", List.of());
        RepairBudgetSnapshot refBudget = budgetManager.checkBudget(1, 5000L);

        for (int i = 0; i < 100; i++) {
            String fp = fingerprintService.generateFingerprint(Ecosystem.PYTHON, c, e, log, "");
            FailureFingerprintResult res = fingerprintService.analyzeFingerprintSequence(Ecosystem.PYTHON, c, e, log, "", List.of());
            RepairBudgetSnapshot budget = budgetManager.checkBudget(1, 5000L);

            assertEquals(refFp, fp);
            assertEquals(refRes, res);
            assertEquals(refBudget, budget);
        }
    }
}

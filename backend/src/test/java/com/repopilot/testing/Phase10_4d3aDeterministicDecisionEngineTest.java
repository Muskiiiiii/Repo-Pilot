package com.repopilot.testing;

import com.repopilot.testing.dto.FailureClassification;
import com.repopilot.testing.entity.ConfidenceLevel;
import com.repopilot.testing.entity.FailureType;
import com.repopilot.testing.entity.Repairability;
import com.repopilot.testing.entity.TerminationReason;
import com.repopilot.testing.parser.ParsedTestEvidence;
import com.repopilot.testing.policy.D3DecisionEngine;
import com.repopilot.testing.policy.D3DecisionResult;
import com.repopilot.testing.policy.D3DecisionType;
import com.repopilot.testing.policy.D3ExecutionFacts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Phase10_4d3aDeterministicDecisionEngineTest {

    private D3DecisionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new D3DecisionEngine();
    }

    private FailureClassification createClassification(FailureType category, Repairability repairability) {
        return new FailureClassification(
                category, repairability, ConfidenceLevel.HIGH,
                "Reason for " + category, null, "10.3-v1",
                Instant.now(), true, Map.of()
        );
    }

    @Test
    @DisplayName("Scenario 1: Clean pass satisfying all 18 invariants returns REPAIR_SUCCESS")
    void test01_CleanPass_ReturnsRepairSuccess() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .baselineFailingFingerprints(Set.of("test_calc.py:ZeroDivisionError"))
                .currentFailingFingerprints(Set.of())
                .currentClassification(createClassification(FailureType.PASS, Repairability.NOT_REPAIRABLE))
                .currentParsedEvidence(new ParsedTestEvidence("PARSED", 5, 0, 0, 0, 5, Map.of()))
                .exitCode(0)
                .terminationReason(TerminationReason.NORMAL_EXIT)
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_SUCCESS", result.getOutcomePolicy());
        assertNull(result.getNextIterationNumber());
    }

    @Test
    @DisplayName("Scenario 2: Repairable TEST_FAILURE with persisting baseline failure returns CONTINUE SAME_FAILURE")
    void test02_RepairableTestFailure_ReturnsContinueSameFailure() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .baselineFailingFingerprints(Set.of("test_calc.py:AssertionError"))
                .currentFailingFingerprints(Set.of("test_calc.py:AssertionError"))
                .currentClassification(createClassification(FailureType.TEST_FAILURE, Repairability.REPAIRABLE))
                .currentParsedEvidence(new ParsedTestEvidence("PARSED", 5, 1, 0, 0, 4, Map.of()))
                .exitCode(1)
                .terminationReason(TerminationReason.NORMAL_EXIT)
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.CONTINUE, result.getDecision());
        assertEquals("SAME_FAILURE", result.getOutcomePolicy());
        assertEquals(2, result.getNextIterationNumber());
    }

    @Test
    @DisplayName("Scenario 3: Repairable CODE_FAILURE returns CONTINUE")
    void test03_RepairableCodeFailure_ReturnsContinue() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .baselineFailingFingerprints(Set.of("calc.py:ZeroDivisionError"))
                .currentFailingFingerprints(Set.of("calc.py:ZeroDivisionError"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .currentParsedEvidence(new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of()))
                .exitCode(1)
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.CONTINUE, result.getDecision());
        assertEquals("SAME_FAILURE", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 4: Repairable COMPILATION_FAILURE returns CONTINUE")
    void test04_RepairableCompilationFailure_ReturnsContinue() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .baselineFailingFingerprints(Set.of("calc.py:SyntaxError"))
                .currentFailingFingerprints(Set.of("calc.py:SyntaxError"))
                .currentClassification(createClassification(FailureType.COMPILATION_FAILURE, Repairability.REPAIRABLE))
                .currentParsedEvidence(ParsedTestEvidence.unparsed())
                .exitCode(1)
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.CONTINUE, result.getDecision());
        assertEquals("SAME_FAILURE", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 5: DEPENDENCY_UNAVAILABLE terminates with REPAIR_FAILED_NON_REPAIRABLE")
    void test05_DependencyUnavailable_ReturnsTerminate() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .currentClassification(createClassification(FailureType.DEPENDENCY_UNAVAILABLE, Repairability.NOT_REPAIRABLE))
                .exitCode(1)
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_FAILED_NON_REPAIRABLE", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 6: NETWORK_REQUIRED terminates with REPAIR_FAILED_NON_REPAIRABLE")
    void test06_NetworkRequired_ReturnsTerminate() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .currentClassification(createClassification(FailureType.NETWORK_REQUIRED, Repairability.NOT_REPAIRABLE))
                .exitCode(1)
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_FAILED_NON_REPAIRABLE", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 7: TIMEOUT terminates with REPAIR_FAILED_TIMEOUT")
    void test07_Timeout_ReturnsTerminate() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .terminationReason(TerminationReason.TIMEOUT)
                .currentClassification(createClassification(FailureType.TIMEOUT, Repairability.NOT_REPAIRABLE))
                .exitCode(124)
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_FAILED_TIMEOUT", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 8: OOM terminates with REPAIR_FAILED_OOM")
    void test08_OOM_ReturnsTerminate() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .terminationReason(TerminationReason.OOM)
                .currentClassification(createClassification(FailureType.OOM, Repairability.NOT_REPAIRABLE))
                .exitCode(137)
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_FAILED_OOM", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 9: RESOURCE_LIMIT terminates with REPAIR_FAILED_RESOURCE_LIMIT")
    void test09_ResourceLimit_ReturnsTerminate() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .terminationReason(TerminationReason.RESOURCE_LIMIT)
                .currentClassification(createClassification(FailureType.RESOURCE_LIMIT, Repairability.NOT_REPAIRABLE))
                .exitCode(1)
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_FAILED_RESOURCE_LIMIT", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 10: EXECUTION_INFRA_FAILURE terminates with REPAIR_FAILED_INFRASTRUCTURE")
    void test10_ExecutionInfraFailure_ReturnsTerminate() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .terminationReason(TerminationReason.EXECUTION_INFRA_FAILURE)
                .currentClassification(createClassification(FailureType.EXECUTION_INFRA_FAILURE, Repairability.NOT_REPAIRABLE))
                .exitCode(1)
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_FAILED_INFRASTRUCTURE", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 11: CANCELLED terminates with REPAIR_CANCELLED")
    void test11_Cancelled_ReturnsTerminate() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .isCancelled(true)
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_CANCELLED", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 12: UNKNOWN classification terminates with REPAIR_FAILED_AMBIGUOUS")
    void test12_UnknownClassification_ReturnsTerminate() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .currentClassification(createClassification(FailureType.UNKNOWN, Repairability.AMBIGUOUS))
                .exitCode(1)
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_FAILED_AMBIGUOUS", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 13: AMBIGUOUS repairability terminates with REPAIR_FAILED_AMBIGUOUS")
    void test13_AmbiguousRepairability_ReturnsTerminate() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.AMBIGUOUS))
                .exitCode(1)
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_FAILED_AMBIGUOUS", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 14: Security rejected terminates with REPAIR_SECURITY_REJECTED")
    void test14_SecurityRejected_ReturnsTerminate() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .isSecurityRejected(true)
                .rejectionReason("SECURITY_INVARIANT_VIOLATION: File creation is prohibited")
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_SECURITY_REJECTED", result.getOutcomePolicy());
        assertTrue(result.getDecisionReason().contains("File creation is prohibited"));
    }

    @Test
    @DisplayName("Scenario 15: Iteration 1 allowed to CONTINUE to iteration 2")
    void test15_Iteration1Allowed_ReturnsContinueToIteration2() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .baselineFailingFingerprints(Set.of("test1"))
                .currentFailingFingerprints(Set.of("test1"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.CONTINUE, result.getDecision());
        assertEquals(1, result.getIterationNumber());
        assertEquals(2, result.getNextIterationNumber());
    }

    @Test
    @DisplayName("Scenario 16: Iteration 2 allowed to CONTINUE to iteration 3")
    void test16_Iteration2Allowed_ReturnsContinueToIteration3() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(2)
                .baselineFailingFingerprints(Set.of("test1"))
                .currentFailingFingerprints(Set.of("test1"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.CONTINUE, result.getDecision());
        assertEquals(2, result.getIterationNumber());
        assertEquals(3, result.getNextIterationNumber());
    }

    @Test
    @DisplayName("Scenario 17: Iteration 3 limit reached terminates with MAX_ITERATIONS_REACHED")
    void test17_Iteration3LimitReached_ReturnsTerminateMaxIterations() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(3)
                .baselineFailingFingerprints(Set.of("test1"))
                .currentFailingFingerprints(Set.of("test1"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("MAX_ITERATIONS_REACHED", result.getOutcomePolicy());
        assertNull(result.getNextIterationNumber());
    }

    @Test
    @DisplayName("Scenario 18: Iteration 4 rejected mechanically with MAX_ITERATIONS_REACHED")
    void test18_Iteration4Rejected_ReturnsTerminateMaxIterations() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(4)
                .baselineFailingFingerprints(Set.of("test1"))
                .currentFailingFingerprints(Set.of("test1"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("MAX_ITERATIONS_REACHED", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 19: Baseline A -> Current empty returns REPAIR_SUCCESS")
    void test19_BaselineA_CurrentEmpty_ReturnsRepairSuccess() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .baselineFailingFingerprints(Set.of("testA"))
                .currentFailingFingerprints(Set.of())
                .currentClassification(createClassification(FailureType.PASS, Repairability.NOT_REPAIRABLE))
                .currentParsedEvidence(new ParsedTestEvidence("PARSED", 2, 0, 0, 0, 2, Map.of()))
                .exitCode(0)
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_SUCCESS", result.getOutcomePolicy());
        assertTrue(result.getResolvedFailures().contains("testA"));
        assertTrue(result.getNewFailures().isEmpty());
    }

    @Test
    @DisplayName("Scenario 20: Baseline A -> Current A returns SAME_FAILURE")
    void test20_BaselineA_CurrentA_ReturnsSameFailure() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .baselineFailingFingerprints(Set.of("testA"))
                .currentFailingFingerprints(Set.of("testA"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.CONTINUE, result.getDecision());
        assertEquals("SAME_FAILURE", result.getOutcomePolicy());
        assertTrue(result.getPersistingFailures().contains("testA"));
    }

    @Test
    @DisplayName("Scenario 21: Baseline A -> Current B returns REGRESSION")
    void test21_BaselineA_CurrentB_ReturnsRegression() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .baselineFailingFingerprints(Set.of("testA"))
                .currentFailingFingerprints(Set.of("testB"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REGRESSION", result.getOutcomePolicy());
        assertTrue(result.getNewFailures().contains("testB"));
    }

    @Test
    @DisplayName("Scenario 22: Baseline A,B -> Current A returns FEWER_FAILURES")
    void test22_BaselineAB_CurrentA_ReturnsFewerFailures() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .baselineFailingFingerprints(Set.of("testA", "testB"))
                .currentFailingFingerprints(Set.of("testA"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.CONTINUE, result.getDecision());
        assertEquals("FEWER_FAILURES", result.getOutcomePolicy());
        assertTrue(result.getResolvedFailures().contains("testB"));
    }

    @Test
    @DisplayName("Scenario 23: Baseline A -> Current A,B returns REGRESSION")
    void test23_BaselineA_CurrentAB_ReturnsRegression() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .baselineFailingFingerprints(Set.of("testA"))
                .currentFailingFingerprints(Set.of("testA", "testB"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REGRESSION", result.getOutcomePolicy());
        assertTrue(result.getNewFailures().contains("testB"));
    }

    @Test
    @DisplayName("Scenario 24: Empty Baseline -> Current A returns REGRESSION")
    void test24_EmptyBaseline_CurrentA_ReturnsRegression() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .baselineFailingFingerprints(Set.of())
                .currentFailingFingerprints(Set.of("testA"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REGRESSION", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 25: Cancellation overrides continuation")
    void test25_CancellationOverridesContinuation() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .isCancelled(true)
                .baselineFailingFingerprints(Set.of("testA"))
                .currentFailingFingerprints(Set.of("testA"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_CANCELLED", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 26: Cumulative budget exhaustion overrides continuation")
    void test26_BudgetExhaustionOverridesContinuation() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .isBudgetExceeded(true)
                .baselineFailingFingerprints(Set.of("testA"))
                .currentFailingFingerprints(Set.of("testA"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("CUMULATIVE_TIMEOUT", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 27: Exit code 0 with UNPARSED parser result cannot succeed")
    void test27_ExitCodeZeroUnparsed_CannotSucceed() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .exitCode(0)
                .currentParsedEvidence(ParsedTestEvidence.unparsed())
                .currentClassification(createClassification(FailureType.PASS, Repairability.NOT_REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_FAILED_AMBIGUOUS", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 28: Invalid workspace integrity cannot succeed")
    void test28_InvalidWorkspaceIntegrity_CannotSucceed() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .isWorkspaceIntegrityValid(false)
                .exitCode(0)
                .currentParsedEvidence(new ParsedTestEvidence("PARSED", 5, 0, 0, 0, 5, Map.of()))
                .currentClassification(createClassification(FailureType.PASS, Repairability.NOT_REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_SECURITY_REJECTED", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 29: Invalid master integrity cannot succeed")
    void test29_InvalidMasterIntegrity_CannotSucceed() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .isMasterIntegrityValid(false)
                .exitCode(0)
                .currentParsedEvidence(new ParsedTestEvidence("PARSED", 5, 0, 0, 0, 5, Map.of()))
                .currentClassification(createClassification(FailureType.PASS, Repairability.NOT_REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_SECURITY_REJECTED", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 30: Invalid proposal binding cannot succeed")
    void test30_InvalidProposalBinding_CannotSucceed() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(1)
                .isProposalBindingValid(false)
                .exitCode(0)
                .currentParsedEvidence(new ParsedTestEvidence("PARSED", 5, 0, 0, 0, 5, Map.of()))
                .currentClassification(createClassification(FailureType.PASS, Repairability.NOT_REPAIRABLE))
                .build();

        D3DecisionResult result = engine.evaluate(facts);

        assertEquals(D3DecisionType.TERMINATE, result.getDecision());
        assertEquals("REPAIR_SECURITY_REJECTED", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 31: Property & Determinism Test (100 repeated evaluations yield identical output)")
    void test31_PropertyDeterminismTest() {
        D3ExecutionFacts facts = D3ExecutionFacts.builder()
                .iterationNumber(2)
                .baselineFailingFingerprints(Set.of("test1", "test2"))
                .currentFailingFingerprints(Set.of("test1"))
                .currentClassification(createClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE))
                .currentParsedEvidence(new ParsedTestEvidence("PARSED", 10, 1, 0, 0, 9, Map.of()))
                .exitCode(1)
                .build();

        D3DecisionResult referenceResult = engine.evaluate(facts);

        for (int i = 0; i < 100; i++) {
            D3DecisionResult repeatedResult = engine.evaluate(facts);
            assertEquals(referenceResult, repeatedResult, "Evaluation #" + i + " must be 100% identical");
            assertEquals(referenceResult.hashCode(), repeatedResult.hashCode());
            assertEquals(referenceResult.toString(), repeatedResult.toString());
        }
    }
}

package com.repopilot.testing.policy;

import com.repopilot.testing.dto.FailureClassification;
import com.repopilot.testing.entity.FailureType;
import com.repopilot.testing.entity.Repairability;
import com.repopilot.testing.entity.TerminationReason;
import com.repopilot.testing.parser.ParsedTestEvidence;

import java.util.HashSet;
import java.util.Set;

public class D3DecisionEngine {

    public static final int MAX_REPAIR_ITERATIONS = 3;

    public D3DecisionResult evaluate(D3ExecutionFacts facts) {
        if (facts == null) {
            return D3DecisionResult.terminate("REPAIR_FAILED_AMBIGUOUS", "Execution facts object is null", Set.of(), Set.of(), Set.of(), 0);
        }

        int iteration = facts.getIterationNumber();
        Set<String> B = facts.getBaselineFailingFingerprints();
        Set<String> C = facts.getCurrentFailingFingerprints();

        Set<String> newFailures = new HashSet<>(C);
        newFailures.removeAll(B);

        Set<String> resolvedFailures = new HashSet<>(B);
        resolvedFailures.removeAll(C);

        Set<String> persistingFailures = new HashSet<>(B);
        persistingFailures.retainAll(C);

        // PRECEDENCE 1: Security & Lineage Violations
        if (facts.isSecurityRejected() || !facts.isMasterIntegrityValid() || !facts.isWorkspaceIntegrityValid() || !facts.isProposalBindingValid()) {
            String reason = facts.getRejectionReason() != null && !facts.getRejectionReason().isBlank()
                    ? facts.getRejectionReason()
                    : "Security or tree integrity invariant violation";
            return D3DecisionResult.terminate("REPAIR_SECURITY_REJECTED", reason, newFailures, resolvedFailures, persistingFailures, iteration);
        }

        // PRECEDENCE 2: User/System Cancellation
        if (facts.isCancelled()) {
            return D3DecisionResult.terminate("REPAIR_CANCELLED", "Execution was cancelled by user or system", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        // PRECEDENCE 3: Cumulative Time Budget Exhaustion
        if (facts.isBudgetExceeded()) {
            return D3DecisionResult.terminate("CUMULATIVE_TIMEOUT", "Cumulative execution time budget (300s) exhausted", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        // PRECEDENCE 4: Iteration Upper Limit Guard (> 3)
        if (iteration > MAX_REPAIR_ITERATIONS) {
            return D3DecisionResult.terminate("MAX_ITERATIONS_REACHED", "Iteration number (" + iteration + ") exceeds maximum allowed repair iterations (" + MAX_REPAIR_ITERATIONS + ")", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        TerminationReason termReason = facts.getTerminationReason();
        FailureClassification classification = facts.getCurrentClassification();
        FailureType category = classification != null ? classification.getFailureCategory() : FailureType.UNKNOWN;

        // PRECEDENCE 5: Hard Container / Infrastructure / Non-Repairable Failures
        if (termReason == TerminationReason.TIMEOUT || category == FailureType.TIMEOUT) {
            return D3DecisionResult.terminate("REPAIR_FAILED_TIMEOUT", "Execution timed out after exceeding configured limit", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        if (termReason == TerminationReason.OOM || category == FailureType.OOM) {
            return D3DecisionResult.terminate("REPAIR_FAILED_OOM", "Container execution exhausted memory allocation limit", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        if (termReason == TerminationReason.RESOURCE_LIMIT || termReason == TerminationReason.PID_LIMIT || category == FailureType.RESOURCE_LIMIT) {
            return D3DecisionResult.terminate("REPAIR_FAILED_RESOURCE_LIMIT", "Sandbox container resource limit reached", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        if (termReason == TerminationReason.EXECUTION_INFRA_FAILURE || category == FailureType.EXECUTION_INFRA_FAILURE) {
            return D3DecisionResult.terminate("REPAIR_FAILED_INFRASTRUCTURE", "Infrastructure failure encountered during container execution", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        if (category == FailureType.DEPENDENCY_UNAVAILABLE || category == FailureType.NETWORK_REQUIRED) {
            return D3DecisionResult.terminate("REPAIR_FAILED_NON_REPAIRABLE", "Execution requires external network or missing dependency", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        if (classification != null && (classification.getRepairability() == Repairability.AMBIGUOUS || category == FailureType.UNKNOWN)) {
            return D3DecisionResult.terminate("REPAIR_FAILED_AMBIGUOUS", "Failure classification is ambiguous or unknown", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        // PRECEDENCE 6: 18-Invariant Success Check
        Integer exitCode = facts.getExitCode();
        ParsedTestEvidence evidence = facts.getCurrentParsedEvidence();
        boolean isExitZero = exitCode != null && exitCode == 0;
        boolean isNormalExit = termReason == TerminationReason.NORMAL_EXIT;
        boolean isParsed = evidence != null && "PARSED".equalsIgnoreCase(evidence.getParserStatus());
        boolean hasZeroFailures = evidence != null && evidence.getFailuresCount() == 0 && evidence.getErrorsCount() == 0;
        boolean hasRunTests = evidence != null && evidence.getTestsRun() > 0;
        boolean isCategoryPass = category == FailureType.PASS;

        if (isExitZero && isNormalExit && isParsed && hasZeroFailures && hasRunTests && isCategoryPass && C.isEmpty() && newFailures.isEmpty()) {
            return D3DecisionResult.terminate("REPAIR_SUCCESS", "All 18 security, integrity, parser, and failure resolution invariants satisfied. Clean exit 0 with 0 test failures.", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        // If exitCode is 0 but fails any success invariant (e.g. UNPARSED or failures > 0), reject success -> AMBIGUOUS
        if (isExitZero && (isCategoryPass || !isParsed || evidence == null || evidence.getFailuresCount() > 0 || evidence.getErrorsCount() > 0 || !C.isEmpty())) {
            return D3DecisionResult.terminate("REPAIR_FAILED_AMBIGUOUS", "Exit code 0 but test parser output is invalid, unparsed, or indicates failing tests", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        // PRECEDENCE 7: NOT_REPAIRABLE Failure Category (Excluding PASS)
        if (classification != null && classification.getRepairability() == Repairability.NOT_REPAIRABLE && category != FailureType.PASS) {
            return D3DecisionResult.terminate("REPAIR_FAILED_NON_REPAIRABLE", "Failure classification is not repairable", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        // PRECEDENCE 8: Set Regression Check (NEW_FAILURES != empty)
        if (!newFailures.isEmpty()) {
            return D3DecisionResult.terminate("REGRESSION", "Canonical baseline set regression detected. Introduced " + newFailures.size() + " new test failure(s): " + newFailures, newFailures, resolvedFailures, persistingFailures, iteration);
        }

        // PRECEDENCE 9: Cycle & No-Progress Check
        if (facts.isCycleDetected()) {
            return D3DecisionResult.terminate("REPEATED_FAILURE", "Failure cycle or repeated failure fingerprint detected", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        if (facts.isNoProgressDetected()) {
            return D3DecisionResult.terminate("REPAIR_NO_PROGRESS", "No behavioral progress made by repair proposal", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        // PRECEDENCE 10: Max Iterations Limit Reached (iteration == 3)
        if (iteration >= MAX_REPAIR_ITERATIONS) {
            return D3DecisionResult.terminate("MAX_ITERATIONS_REACHED", "Maximum repair iteration limit reached (" + MAX_REPAIR_ITERATIONS + ")", newFailures, resolvedFailures, persistingFailures, iteration);
        }

        // PRECEDENCE 11: Continuation Decision (Eligible Repairable Failure)
        if (classification != null && classification.getRepairability() == Repairability.REPAIRABLE &&
                (category == FailureType.CODE_FAILURE || category == FailureType.TEST_FAILURE || category == FailureType.COMPILATION_FAILURE)) {

            if (!resolvedFailures.isEmpty()) {
                return D3DecisionResult.continueToNext("FEWER_FAILURES", "Partial progress made: " + resolvedFailures.size() + " failure(s) resolved. " + C.size() + " failure(s) remain.", newFailures, resolvedFailures, persistingFailures, iteration);
            } else {
                return D3DecisionResult.continueToNext("SAME_FAILURE", "Repairable failure persists without new regressions. Eligible for next iteration.", newFailures, resolvedFailures, persistingFailures, iteration);
            }
        }

        return D3DecisionResult.terminate("REPAIR_FAILED_AMBIGUOUS", "Execution did not satisfy continuation requirements", newFailures, resolvedFailures, persistingFailures, iteration);
    }
}

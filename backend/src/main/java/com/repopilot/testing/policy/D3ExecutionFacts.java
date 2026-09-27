package com.repopilot.testing.policy;

import com.repopilot.testing.dto.FailureClassification;
import com.repopilot.testing.entity.TerminationReason;
import com.repopilot.testing.parser.ParsedTestEvidence;

import java.util.Collections;
import java.util.Set;

public class D3ExecutionFacts {

    private final int iterationNumber;
    private final int maxIterations;
    private final Set<String> baselineFailingFingerprints;
    private final Set<String> currentFailingFingerprints;
    private final FailureClassification currentClassification;
    private final ParsedTestEvidence currentParsedEvidence;
    private final TerminationReason terminationReason;
    private final Integer exitCode;
    private final boolean isCancelled;
    private final boolean isBudgetExceeded;
    private final boolean isSecurityRejected;
    private final boolean isMasterIntegrityValid;
    private final boolean isWorkspaceIntegrityValid;
    private final boolean isProposalBindingValid;
    private final boolean isNoProgressDetected;
    private final boolean isCycleDetected;
    private final String rejectionReason;

    public D3ExecutionFacts(int iterationNumber,
                            int maxIterations,
                            Set<String> baselineFailingFingerprints,
                            Set<String> currentFailingFingerprints,
                            FailureClassification currentClassification,
                            ParsedTestEvidence currentParsedEvidence,
                            TerminationReason terminationReason,
                            Integer exitCode,
                            boolean isCancelled,
                            boolean isBudgetExceeded,
                            boolean isSecurityRejected,
                            boolean isMasterIntegrityValid,
                            boolean isWorkspaceIntegrityValid,
                            boolean isProposalBindingValid,
                            boolean isNoProgressDetected,
                            boolean isCycleDetected,
                            String rejectionReason) {
        this.iterationNumber = iterationNumber;
        this.maxIterations = maxIterations > 0 ? maxIterations : 3;
        this.baselineFailingFingerprints = baselineFailingFingerprints != null ? Set.copyOf(baselineFailingFingerprints) : Collections.emptySet();
        this.currentFailingFingerprints = currentFailingFingerprints != null ? Set.copyOf(currentFailingFingerprints) : Collections.emptySet();
        this.currentClassification = currentClassification;
        this.currentParsedEvidence = currentParsedEvidence;
        this.terminationReason = terminationReason != null ? terminationReason : TerminationReason.NORMAL_EXIT;
        this.exitCode = exitCode;
        this.isCancelled = isCancelled;
        this.isBudgetExceeded = isBudgetExceeded;
        this.isSecurityRejected = isSecurityRejected;
        this.isMasterIntegrityValid = isMasterIntegrityValid;
        this.isWorkspaceIntegrityValid = isWorkspaceIntegrityValid;
        this.isProposalBindingValid = isProposalBindingValid;
        this.isNoProgressDetected = isNoProgressDetected;
        this.isCycleDetected = isCycleDetected;
        this.rejectionReason = rejectionReason;
    }

    public int getIterationNumber() { return iterationNumber; }
    public int getMaxIterations() { return maxIterations; }
    public Set<String> getBaselineFailingFingerprints() { return baselineFailingFingerprints; }
    public Set<String> getCurrentFailingFingerprints() { return currentFailingFingerprints; }
    public FailureClassification getCurrentClassification() { return currentClassification; }
    public ParsedTestEvidence getCurrentParsedEvidence() { return currentParsedEvidence; }
    public TerminationReason getTerminationReason() { return terminationReason; }
    public Integer getExitCode() { return exitCode; }
    public boolean isCancelled() { return isCancelled; }
    public boolean isBudgetExceeded() { return isBudgetExceeded; }
    public boolean isSecurityRejected() { return isSecurityRejected; }
    public boolean isMasterIntegrityValid() { return isMasterIntegrityValid; }
    public boolean isWorkspaceIntegrityValid() { return isWorkspaceIntegrityValid; }
    public boolean isProposalBindingValid() { return isProposalBindingValid; }
    public boolean isNoProgressDetected() { return isNoProgressDetected; }
    public boolean isCycleDetected() { return isCycleDetected; }
    public String getRejectionReason() { return rejectionReason; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int iterationNumber = 1;
        private int maxIterations = 3;
        private Set<String> baselineFailingFingerprints = Collections.emptySet();
        private Set<String> currentFailingFingerprints = Collections.emptySet();
        private FailureClassification currentClassification;
        private ParsedTestEvidence currentParsedEvidence;
        private TerminationReason terminationReason = TerminationReason.NORMAL_EXIT;
        private Integer exitCode;
        private boolean isCancelled = false;
        private boolean isBudgetExceeded = false;
        private boolean isSecurityRejected = false;
        private boolean isMasterIntegrityValid = true;
        private boolean isWorkspaceIntegrityValid = true;
        private boolean isProposalBindingValid = true;
        private boolean isNoProgressDetected = false;
        private boolean isCycleDetected = false;
        private String rejectionReason;

        public Builder iterationNumber(int val) { this.iterationNumber = val; return this; }
        public Builder maxIterations(int val) { this.maxIterations = val; return this; }
        public Builder baselineFailingFingerprints(Set<String> val) { this.baselineFailingFingerprints = val; return this; }
        public Builder currentFailingFingerprints(Set<String> val) { this.currentFailingFingerprints = val; return this; }
        public Builder currentClassification(FailureClassification val) { this.currentClassification = val; return this; }
        public Builder currentParsedEvidence(ParsedTestEvidence val) { this.currentParsedEvidence = val; return this; }
        public Builder terminationReason(TerminationReason val) { this.terminationReason = val; return this; }
        public Builder exitCode(Integer val) { this.exitCode = val; return this; }
        public Builder isCancelled(boolean val) { this.isCancelled = val; return this; }
        public Builder isBudgetExceeded(boolean val) { this.isBudgetExceeded = val; return this; }
        public Builder isSecurityRejected(boolean val) { this.isSecurityRejected = val; return this; }
        public Builder isMasterIntegrityValid(boolean val) { this.isMasterIntegrityValid = val; return this; }
        public Builder isWorkspaceIntegrityValid(boolean val) { this.isWorkspaceIntegrityValid = val; return this; }
        public Builder isProposalBindingValid(boolean val) { this.isProposalBindingValid = val; return this; }
        public Builder isNoProgressDetected(boolean val) { this.isNoProgressDetected = val; return this; }
        public Builder isCycleDetected(boolean val) { this.isCycleDetected = val; return this; }
        public Builder rejectionReason(String val) { this.rejectionReason = val; return this; }

        public D3ExecutionFacts build() {
            return new D3ExecutionFacts(
                    iterationNumber, maxIterations, baselineFailingFingerprints, currentFailingFingerprints,
                    currentClassification, currentParsedEvidence, terminationReason, exitCode,
                    isCancelled, isBudgetExceeded, isSecurityRejected, isMasterIntegrityValid,
                    isWorkspaceIntegrityValid, isProposalBindingValid, isNoProgressDetected,
                    isCycleDetected, rejectionReason
            );
        }
    }
}

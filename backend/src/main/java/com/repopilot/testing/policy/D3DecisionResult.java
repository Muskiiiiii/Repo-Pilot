package com.repopilot.testing.policy;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

public class D3DecisionResult {

    private final D3DecisionType decision;
    private final String outcomePolicy;
    private final String decisionReason;
    private final Set<String> newFailures;
    private final Set<String> resolvedFailures;
    private final Set<String> persistingFailures;
    private final int iterationNumber;
    private final Integer nextIterationNumber;

    public D3DecisionResult(D3DecisionType decision,
                            String outcomePolicy,
                            String decisionReason,
                            Set<String> newFailures,
                            Set<String> resolvedFailures,
                            Set<String> persistingFailures,
                            int iterationNumber,
                            Integer nextIterationNumber) {
        this.decision = Objects.requireNonNull(decision, "decision cannot be null");
        this.outcomePolicy = Objects.requireNonNull(outcomePolicy, "outcomePolicy cannot be null");
        this.decisionReason = decisionReason != null ? decisionReason : "";
        this.newFailures = newFailures != null ? Set.copyOf(newFailures) : Collections.emptySet();
        this.resolvedFailures = resolvedFailures != null ? Set.copyOf(resolvedFailures) : Collections.emptySet();
        this.persistingFailures = persistingFailures != null ? Set.copyOf(persistingFailures) : Collections.emptySet();
        this.iterationNumber = iterationNumber;
        this.nextIterationNumber = nextIterationNumber;
    }

    public static D3DecisionResult terminate(String outcomePolicy, String reason, Set<String> newFailures, Set<String> resolvedFailures, Set<String> persistingFailures, int iterationNumber) {
        return new D3DecisionResult(D3DecisionType.TERMINATE, outcomePolicy, reason, newFailures, resolvedFailures, persistingFailures, iterationNumber, null);
    }

    public static D3DecisionResult continueToNext(String outcomePolicy, String reason, Set<String> newFailures, Set<String> resolvedFailures, Set<String> persistingFailures, int iterationNumber) {
        return new D3DecisionResult(D3DecisionType.CONTINUE, outcomePolicy, reason, newFailures, resolvedFailures, persistingFailures, iterationNumber, iterationNumber + 1);
    }

    public D3DecisionType getDecision() { return decision; }
    public String getOutcomePolicy() { return outcomePolicy; }
    public String getDecisionReason() { return decisionReason; }
    public Set<String> getNewFailures() { return newFailures; }
    public Set<String> getResolvedFailures() { return resolvedFailures; }
    public Set<String> getPersistingFailures() { return persistingFailures; }
    public int getIterationNumber() { return iterationNumber; }
    public Integer getNextIterationNumber() { return nextIterationNumber; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        D3DecisionResult that = (D3DecisionResult) o;
        return iterationNumber == that.iterationNumber &&
                decision == that.decision &&
                Objects.equals(outcomePolicy, that.outcomePolicy) &&
                Objects.equals(decisionReason, that.decisionReason) &&
                Objects.equals(newFailures, that.newFailures) &&
                Objects.equals(resolvedFailures, that.resolvedFailures) &&
                Objects.equals(persistingFailures, that.persistingFailures) &&
                Objects.equals(nextIterationNumber, that.nextIterationNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(decision, outcomePolicy, decisionReason, newFailures, resolvedFailures, persistingFailures, iterationNumber, nextIterationNumber);
    }

    @Override
    public String toString() {
        return "D3DecisionResult{" +
                "decision=" + decision +
                ", outcomePolicy='" + outcomePolicy + '\'' +
                ", decisionReason='" + decisionReason + '\'' +
                ", newFailures=" + newFailures +
                ", resolvedFailures=" + resolvedFailures +
                ", persistingFailures=" + persistingFailures +
                ", iterationNumber=" + iterationNumber +
                ", nextIterationNumber=" + nextIterationNumber +
                '}';
    }
}

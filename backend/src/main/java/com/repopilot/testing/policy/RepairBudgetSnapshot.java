package com.repopilot.testing.policy;

import java.util.Objects;

public class RepairBudgetSnapshot {

    public static final int MAX_REPAIRS = 3;
    public static final long CUMULATIVE_TIME_CEILING_MS = 300_000L; // 300 seconds

    private final int maxRepairs;
    private final int repairsConsumed;
    private final int repairsRemaining;
    private final int totalExecutions;
    private final long cumulativeExecutionMillis;
    private final long remainingExecutionMillis;
    private final boolean repairAllowed;
    private final String denialReason;

    public RepairBudgetSnapshot(int maxRepairs,
                                int repairsConsumed,
                                int totalExecutions,
                                long cumulativeExecutionMillis,
                                boolean repairAllowed,
                                String denialReason) {
        this.maxRepairs = maxRepairs > 0 ? maxRepairs : MAX_REPAIRS;
        this.repairsConsumed = Math.max(0, repairsConsumed);
        this.repairsRemaining = Math.max(0, this.maxRepairs - this.repairsConsumed);
        this.totalExecutions = Math.max(1, totalExecutions);
        this.cumulativeExecutionMillis = Math.max(0L, cumulativeExecutionMillis);
        this.remainingExecutionMillis = Math.max(0L, CUMULATIVE_TIME_CEILING_MS - this.cumulativeExecutionMillis);
        this.repairAllowed = repairAllowed && this.repairsRemaining > 0 && this.remainingExecutionMillis > 0;
        this.denialReason = denialReason != null ? denialReason : "";
    }

    public int getMaxRepairs() { return maxRepairs; }
    public int getRepairsConsumed() { return repairsConsumed; }
    public int getRepairsRemaining() { return repairsRemaining; }
    public int getTotalExecutions() { return totalExecutions; }
    public long getCumulativeExecutionMillis() { return cumulativeExecutionMillis; }
    public long getRemainingExecutionMillis() { return remainingExecutionMillis; }
    public boolean isRepairAllowed() { return repairAllowed; }
    public String getDenialReason() { return denialReason; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RepairBudgetSnapshot that = (RepairBudgetSnapshot) o;
        return maxRepairs == that.maxRepairs &&
                repairsConsumed == that.repairsConsumed &&
                repairsRemaining == that.repairsRemaining &&
                totalExecutions == that.totalExecutions &&
                cumulativeExecutionMillis == that.cumulativeExecutionMillis &&
                remainingExecutionMillis == that.remainingExecutionMillis &&
                repairAllowed == that.repairAllowed &&
                Objects.equals(denialReason, that.denialReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxRepairs, repairsConsumed, repairsRemaining, totalExecutions, cumulativeExecutionMillis, remainingExecutionMillis, repairAllowed, denialReason);
    }

    @Override
    public String toString() {
        return "RepairBudgetSnapshot{" +
                "maxRepairs=" + maxRepairs +
                ", repairsConsumed=" + repairsConsumed +
                ", repairsRemaining=" + repairsRemaining +
                ", totalExecutions=" + totalExecutions +
                ", cumulativeExecutionMillis=" + cumulativeExecutionMillis +
                ", remainingExecutionMillis=" + remainingExecutionMillis +
                ", repairAllowed=" + repairAllowed +
                ", denialReason='" + denialReason + '\'' +
                '}';
    }
}

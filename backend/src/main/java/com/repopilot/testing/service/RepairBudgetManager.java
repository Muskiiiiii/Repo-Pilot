package com.repopilot.testing.service;

import com.repopilot.testing.policy.RepairBudgetSnapshot;

import java.util.UUID;

public interface RepairBudgetManager {

    RepairBudgetSnapshot getBudgetSnapshot(UUID taskExecutionId);

    RepairBudgetSnapshot consumeRepairBudget(UUID taskExecutionId, long currentExecutionMillis);

    RepairBudgetSnapshot checkBudget(int repairsConsumed, long cumulativeTimeMs);
}

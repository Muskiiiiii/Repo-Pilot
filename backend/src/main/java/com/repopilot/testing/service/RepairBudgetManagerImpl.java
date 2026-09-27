package com.repopilot.testing.service;

import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.testing.entity.TestRun;
import com.repopilot.testing.policy.RepairBudgetSnapshot;
import com.repopilot.testing.repository.RepairIterationRepository;
import com.repopilot.testing.repository.TestRunRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RepairBudgetManagerImpl implements RepairBudgetManager {

    private final RepairIterationRepository repairIterationRepository;
    private final TestRunRepository testRunRepository;
    private final TaskExecutionRepository taskExecutionRepository;

    @Autowired
    public RepairBudgetManagerImpl(RepairIterationRepository repairIterationRepository,
                                   TestRunRepository testRunRepository,
                                   TaskExecutionRepository taskExecutionRepository) {
        this.repairIterationRepository = repairIterationRepository;
        this.testRunRepository = testRunRepository;
        this.taskExecutionRepository = taskExecutionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public RepairBudgetSnapshot getBudgetSnapshot(UUID taskExecutionId) {
        if (taskExecutionId == null) {
            return checkBudget(0, 0L);
        }

        long repairsConsumed = repairIterationRepository != null
                ? repairIterationRepository.countByTaskExecutionId(taskExecutionId)
                : 0L;

        long cumulativeMillis = 0L;
        if (testRunRepository != null) {
            List<TestRun> runs = testRunRepository.findByTaskExecutionId(taskExecutionId);
            for (TestRun r : runs) {
                if (r.getDurationMs() != null) {
                    cumulativeMillis += r.getDurationMs();
                }
            }
        }

        boolean isCancelled = false;
        if (taskExecutionRepository != null) {
            Optional<TaskExecution> execOpt = taskExecutionRepository.findById(taskExecutionId);
            if (execOpt.isPresent() && Boolean.TRUE.equals(execOpt.get().getCancellationRequested())) {
                isCancelled = true;
            }
        }

        if (isCancelled) {
            return new RepairBudgetSnapshot(3, (int) repairsConsumed, (int) (1 + repairsConsumed), cumulativeMillis, false, "Execution cancelled by user or system");
        }

        return checkBudget((int) repairsConsumed, cumulativeMillis);
    }

    @Override
    @Transactional
    public synchronized RepairBudgetSnapshot consumeRepairBudget(UUID taskExecutionId, long currentExecutionMillis) {
        if (taskExecutionId == null) {
            return checkBudget(0, currentExecutionMillis);
        }

        long repairsConsumed = repairIterationRepository != null
                ? repairIterationRepository.countByTaskExecutionId(taskExecutionId)
                : 0L;

        long baseCumulative = 0L;
        if (testRunRepository != null) {
            List<TestRun> runs = testRunRepository.findByTaskExecutionId(taskExecutionId);
            for (TestRun r : runs) {
                if (r.getDurationMs() != null) {
                    baseCumulative += r.getDurationMs();
                }
            }
        }

        long totalCumulative = baseCumulative + Math.max(0L, currentExecutionMillis);

        if (taskExecutionRepository != null) {
            Optional<TaskExecution> execOpt = taskExecutionRepository.findById(taskExecutionId);
            if (execOpt.isPresent() && Boolean.TRUE.equals(execOpt.get().getCancellationRequested())) {
                return new RepairBudgetSnapshot(3, (int) repairsConsumed, (int) (1 + repairsConsumed), totalCumulative, false, "Execution cancelled by user or system");
            }
        }

        return checkBudget((int) repairsConsumed, totalCumulative);
    }

    @Override
    public RepairBudgetSnapshot checkBudget(int repairsConsumed, long cumulativeTimeMs) {
        int safeConsumed = Math.max(0, repairsConsumed);
        long safeTimeMs = Math.max(0L, cumulativeTimeMs);

        boolean allowed = safeConsumed < RepairBudgetSnapshot.MAX_REPAIRS && safeTimeMs < RepairBudgetSnapshot.CUMULATIVE_TIME_CEILING_MS;
        String denialReason = "";

        if (safeConsumed >= RepairBudgetSnapshot.MAX_REPAIRS) {
            denialReason = "Maximum repair iteration limit reached (3)";
        } else if (safeTimeMs >= RepairBudgetSnapshot.CUMULATIVE_TIME_CEILING_MS) {
            denialReason = "Cumulative execution time ceiling (300s) exceeded";
        }

        return new RepairBudgetSnapshot(RepairBudgetSnapshot.MAX_REPAIRS, safeConsumed, 1 + safeConsumed, safeTimeMs, allowed, denialReason);
    }
}

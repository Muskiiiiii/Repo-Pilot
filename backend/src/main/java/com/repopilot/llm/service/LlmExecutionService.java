package com.repopilot.llm.service;

import com.repopilot.llm.dto.LlmExecutionClaimResult;
import com.repopilot.llm.dto.LlmExecutionOutcome;
import com.repopilot.llm.provider.LlmProviderClient;

import java.util.UUID;

public interface LlmExecutionService {

    /**
     * Atomically claims a provider attempt for execution using a database update.
     */
    LlmExecutionClaimResult claimAttemptExecution(UUID attemptId, long leaseDurationMs);

    /**
     * Executes the provider adapter using an active claim result.
     */
    LlmExecutionOutcome executeWithFencing(LlmExecutionClaimResult claimResult, String promptContent, LlmProviderClient providerClient);

    /**
     * Authoritatively finalizes an attempt outcome using lease and fencing version validation.
     */
    boolean finalizeAttemptOutcome(UUID attemptId,
                                   String leaseToken,
                                   Long fencingVersion,
                                   LlmExecutionOutcome outcome);
}

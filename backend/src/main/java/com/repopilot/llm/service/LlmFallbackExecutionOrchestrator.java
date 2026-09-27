package com.repopilot.llm.service;

import com.repopilot.llm.dto.LlmFallbackExecutionResult;
import com.repopilot.llm.dto.LlmReconciliationResult;
import com.repopilot.llm.provider.LlmProviderClient;

import java.util.UUID;

public interface LlmFallbackExecutionOrchestrator {

    /**
     * Orchestrates durable fallback decision creation, capacity reservation, target attempt creation,
     * fencing execution claim, provider invocation, outcome settlement/compensation, and state finalization.
     */
    LlmFallbackExecutionResult orchestrateFallbackExecution(UUID sourceAttemptId,
                                                           Integer decisionSequence,
                                                           String targetProviderId,
                                                           String targetModelId,
                                                           UUID expectedOrganizationId,
                                                           UUID expectedLogicalRequestId,
                                                           String workerId,
                                                           long leaseDurationMs,
                                                           String promptContent,
                                                           LlmProviderClient providerClient);

    /**
     * Recovers an interrupted or crashed fallback decision at any stage of the crash boundary matrix.
     */
    LlmFallbackExecutionResult recoverInterruptedDecision(UUID decisionId,
                                                         String workerId,
                                                         long leaseDurationMs,
                                                         String promptContent,
                                                         LlmProviderClient providerClient);

    /**
     * Performs durable financial and outcome reconciliation for attempts with UNKNOWN_EXTERNAL_OUTCOME.
     */
    LlmReconciliationResult reconcileFallbackAttempt(UUID targetAttemptId,
                                                     String workerId,
                                                     LlmProviderClient providerClient);
}

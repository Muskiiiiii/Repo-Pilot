package com.repopilot.llm.service;

import com.repopilot.llm.dto.LlmFallbackDecisionResult;
import com.repopilot.llm.entity.LlmFallbackDecision;
import com.repopilot.llm.entity.LlmFallbackDecisionStatus;

import java.util.UUID;

public interface LlmFallbackDecisionService {

    /**
     * Evaluates fallback eligibility for an authoritative source attempt and durably creates
     * a fallback decision and target provider attempt.
     */
    LlmFallbackDecisionResult evaluateAndCreateFallbackDecision(UUID sourceAttemptId,
                                                                 Integer decisionSequence,
                                                                 String targetProviderId,
                                                                 String targetModelId,
                                                                 UUID expectedOrganizationId,
                                                                 UUID expectedLogicalRequestId);

    /**
     * Performs same-provider retry coordination.
     */
    LlmFallbackDecisionResult retrySameProvider(UUID sourceAttemptId,
                                                Integer decisionSequence,
                                                UUID expectedOrganizationId,
                                                UUID expectedLogicalRequestId);

    /**
     * Transitions a fallback decision status using fencing version predicate safety.
     */
    boolean transitionDecisionStatus(UUID decisionId,
                                    Long expectedFencingVersion,
                                    LlmFallbackDecisionStatus targetStatus);

    /**
     * Reconciles an incomplete fallback decision.
     */
    LlmFallbackDecision reconcileDecision(UUID decisionId);
}

package com.repopilot.llm.service;

import com.repopilot.llm.dto.LlmReservationResult;
import java.util.UUID;

public interface LlmAccountingService {

    LlmReservationResult reserveCapacity(UUID attemptId, int requestedTokens, long requestedSpendMicroUsd);

    LlmReservationResult reserveCapacityForDecision(UUID fallbackDecisionId, UUID targetAttemptId, int requestedTokens, long requestedSpendMicroUsd);

    LlmReservationResult releaseReservation(UUID attemptId);

    LlmReservationResult settleReservation(UUID attemptId, int actualTokens, long actualSpendMicroUsd);

    LlmReservationResult transferToUnknownExposure(UUID attemptId);

    LlmReservationResult resolveUnknownExposureZeroUsage(UUID attemptId);

    LlmReservationResult resolveUnknownExposureActualUsage(UUID attemptId, int actualTokens, long actualSpendMicroUsd);
}

package com.repopilot.llm.service;

import com.repopilot.llm.dto.LlmExecutionClaimResult;
import com.repopilot.llm.dto.LlmExecutionOutcome;
import com.repopilot.llm.entity.LlmExecutionStatus;
import com.repopilot.llm.entity.LlmProviderAttempt;
import com.repopilot.llm.provider.LlmProviderClient;
import com.repopilot.llm.provider.dto.LlmProviderRequest;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderException;
import com.repopilot.llm.provider.model.LlmProviderFailureReason;
import com.repopilot.llm.repository.LlmProviderAttemptRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class LlmExecutionServiceImpl implements LlmExecutionService {

    private final LlmProviderAttemptRepository attemptRepository;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    public LlmExecutionServiceImpl(LlmProviderAttemptRepository attemptRepository,
                                  PlatformTransactionManager transactionManager) {
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
    }

    @Override
    @Transactional
    public LlmExecutionClaimResult claimAttemptExecution(UUID attemptId, long leaseDurationMs) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        String leaseToken = LlmLeaseTokenGenerator.generateLeaseToken();
        Instant expiresAt = Instant.now().plusMillis(Math.max(1L, leaseDurationMs));

        int affected = attemptRepository.claimAttemptForExecution(
                attemptId,
                leaseToken,
                expiresAt,
                LlmExecutionStatus.ATTEMPT_CREATED,
                LlmExecutionStatus.EXECUTION_CLAIMED,
                Instant.now()
        );

        if (affected == 0) {
            return LlmExecutionClaimResult.rejected("ALREADY_CLAIMED_OR_NOT_EXECUTABLE");
        }

        LlmProviderAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalStateException("Claimed attempt not found: " + attemptId));

        return LlmExecutionClaimResult.success(
                attemptId,
                leaseToken,
                attempt.getFencingVersion(),
                expiresAt,
                attempt
        );
    }

    @Override
    public LlmExecutionOutcome executeWithFencing(LlmExecutionClaimResult claimResult,
                                                 String promptContent,
                                                 LlmProviderClient providerClient) {
        if (claimResult == null || !claimResult.isSuccess()) {
            throw new IllegalArgumentException("Cannot execute with invalid or failed execution claim");
        }
        Objects.requireNonNull(providerClient, "providerClient must not be null");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Integer affected = tx.execute(status ->
                attemptRepository.authorizePreDispatchFencing(
                        claimResult.getAttemptId(),
                        claimResult.getLeaseToken(),
                        claimResult.getFencingVersion(),
                        LlmExecutionStatus.EXECUTION_CLAIMED,
                        LlmExecutionStatus.EXECUTING,
                        Instant.now()
                )
        );

        if (affected == null || affected == 0) {
            return LlmExecutionOutcome.failed(
                    new LlmProviderException(LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH,
                            "Pre-dispatch fencing check failed: lease expired or reclaimed")
            );
        }

        LlmProviderAttempt attempt = claimResult.getAttempt();
        LlmProviderRequest request = new LlmProviderRequest(
                attempt.getProviderId(),
                attempt.getModelId(),
                attempt.getLogicalRequest().getId(),
                attempt.getId(),
                attempt.getProviderIdempotencyKey(),
                attempt.getInputHash(),
                promptContent != null ? promptContent : "",
                attempt.getPromptTokens() != null ? attempt.getPromptTokens() : 0
        );

        try {
            LlmProviderResponse response = providerClient.invoke(request);
            return LlmExecutionOutcome.succeeded(response);
        } catch (LlmProviderException ex) {
            if (ex.getFailureReason() == LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH) {
                return LlmExecutionOutcome.unknownOutcome(ex);
            } else {
                return LlmExecutionOutcome.failed(ex);
            }
        }
    }

    @Override
    @Transactional
    public boolean finalizeAttemptOutcome(UUID attemptId,
                                           String leaseToken,
                                           Long fencingVersion,
                                           LlmExecutionOutcome outcome) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(fencingVersion, "fencingVersion must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");

        Instant endTime = Instant.now();

        if (outcome.getStatus() == LlmExecutionOutcome.Status.SUCCEEDED) {
            LlmProviderResponse resp = outcome.getProviderResponse();
            if (resp == null) {
                throw new IllegalArgumentException("Succeeded outcome must contain non-null provider response");
            }
            int affected = attemptRepository.finalizeAttemptSuccessWithFencing(
                    attemptId,
                    leaseToken,
                    fencingVersion,
                    LlmExecutionStatus.EXECUTION_CLAIMED,
                    LlmExecutionStatus.EXECUTING,
                    LlmExecutionStatus.SUCCEEDED,
                    resp.getProviderRequestId(),
                    resp.getPromptTokens(),
                    resp.getCompletionTokens(),
                    resp.getTotalTokens(),
                    resp.getResponseHash(),
                    endTime,
                    100L
            );
            return affected > 0;
        } else {
            LlmExecutionStatus targetStatus = outcome.getStatus() == LlmExecutionOutcome.Status.UNKNOWN_OUTCOME
                    ? LlmExecutionStatus.TIMED_OUT
                    : LlmExecutionStatus.FAILED;
            String reason = outcome.getFailureReason() != null ? outcome.getFailureReason().name() : "EXECUTION_FAILURE";

            int affected = attemptRepository.finalizeAttemptFailureWithFencing(
                    attemptId,
                    leaseToken,
                    fencingVersion,
                    LlmExecutionStatus.EXECUTION_CLAIMED,
                    LlmExecutionStatus.EXECUTING,
                    targetStatus,
                    reason,
                    endTime
            );
            return affected > 0;
        }
    }
}

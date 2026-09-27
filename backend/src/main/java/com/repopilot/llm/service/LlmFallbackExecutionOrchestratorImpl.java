package com.repopilot.llm.service;

import com.repopilot.llm.dto.LlmExecutionClaimResult;
import com.repopilot.llm.dto.LlmExecutionOutcome;
import com.repopilot.llm.dto.LlmFallbackDecisionResult;
import com.repopilot.llm.dto.LlmFallbackExecutionResult;
import com.repopilot.llm.dto.LlmReconciliationResult;
import com.repopilot.llm.entity.*;
import com.repopilot.llm.provider.LlmProviderClient;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.registry.LlmProviderCapabilityRegistry;
import com.repopilot.llm.repository.AuditLogOutboxRepository;
import com.repopilot.llm.repository.LlmFallbackDecisionRepository;
import com.repopilot.llm.repository.LlmLogicalRequestRepository;
import com.repopilot.llm.repository.LlmProviderAttemptRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class LlmFallbackExecutionOrchestratorImpl implements LlmFallbackExecutionOrchestrator {

    private final LlmFallbackDecisionService fallbackDecisionService;
    private final LlmAccountingService accountingService;
    private final LlmExecutionService executionService;
    private final LlmProviderAttemptRepository providerAttemptRepository;
    private final LlmFallbackDecisionRepository fallbackDecisionRepository;
    private final LlmLogicalRequestRepository logicalRequestRepository;
    private final AuditLogOutboxRepository auditLogOutboxRepository;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    public LlmFallbackExecutionOrchestratorImpl(LlmFallbackDecisionService fallbackDecisionService,
                                                 LlmAccountingService accountingService,
                                                 LlmExecutionService executionService,
                                                 LlmProviderAttemptRepository providerAttemptRepository,
                                                 LlmFallbackDecisionRepository fallbackDecisionRepository,
                                                 LlmLogicalRequestRepository logicalRequestRepository,
                                                 AuditLogOutboxRepository auditLogOutboxRepository,
                                                 PlatformTransactionManager transactionManager) {
        this.fallbackDecisionService = Objects.requireNonNull(fallbackDecisionService, "fallbackDecisionService must not be null");
        this.accountingService = Objects.requireNonNull(accountingService, "accountingService must not be null");
        this.executionService = Objects.requireNonNull(executionService, "executionService must not be null");
        this.providerAttemptRepository = Objects.requireNonNull(providerAttemptRepository, "providerAttemptRepository must not be null");
        this.fallbackDecisionRepository = Objects.requireNonNull(fallbackDecisionRepository, "fallbackDecisionRepository must not be null");
        this.logicalRequestRepository = Objects.requireNonNull(logicalRequestRepository, "logicalRequestRepository must not be null");
        this.auditLogOutboxRepository = auditLogOutboxRepository;
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
    }

    @Override
    public LlmFallbackExecutionResult orchestrateFallbackExecution(UUID sourceAttemptId,
                                                                   Integer decisionSequence,
                                                                   String targetProviderId,
                                                                   String targetModelId,
                                                                   UUID expectedOrganizationId,
                                                                   UUID expectedLogicalRequestId,
                                                                   String workerId,
                                                                   long leaseDurationMs,
                                                                   String promptContent,
                                                                   LlmProviderClient providerClient) {
        // Step 1: Provider Capability Validation
        if (targetProviderId != null && targetModelId != null) {
            try {
                LlmProviderCapabilityRegistry.validateProviderAndModel(targetProviderId, targetModelId);
            } catch (Exception ex) {
                return LlmFallbackExecutionResult.blocked("UNSUPPORTED_TARGET_CAPABILITY", null);
            }
        }

        // Step 2: Decision Evaluation & Target Attempt Derivation
        LlmFallbackDecisionResult decisionResult = fallbackDecisionService.evaluateAndCreateFallbackDecision(
                sourceAttemptId, decisionSequence, targetProviderId, targetModelId, expectedOrganizationId, expectedLogicalRequestId);

        if (!decisionResult.isSuccess()) {
            return LlmFallbackExecutionResult.blocked(decisionResult.getRejectionReason(), decisionResult.getFallbackDecision());
        }

        LlmFallbackDecision decision = decisionResult.getFallbackDecision();
        UUID targetAttemptId = decision.getTargetAttempt() != null ? decision.getTargetAttempt().getId() : null;

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        LlmProviderAttempt targetAttempt = tx.execute(s -> unproxyAttempt(targetAttemptId));

        if (targetAttempt == null) {
            return LlmFallbackExecutionResult.blocked(decisionResult.getRejectionReason() != null ? decisionResult.getRejectionReason() : "MISSING_TARGET_ATTEMPT", decision);
        }

        // Authoritative tenant & parameter validation
        validateAuthoritativeDerivation(decision, targetAttempt, expectedOrganizationId, expectedLogicalRequestId);

        // Step 3: Check Terminal State (Fail Closed & Terminal Immutability)
        if (isTerminalState(decision.getDecisionStatus())) {
            return buildTerminalResult(decision, unproxyAttempt(targetAttempt.getId()));
        }

        // Step 4: Claim Execution & Mark EXECUTING_TARGET_ATTEMPT in short transaction
        LlmExecutionClaimResult claimResult;
        try {
            claimResult = tx.execute(status -> {
                LlmFallbackDecision dec = fallbackDecisionRepository.findById(decision.getId()).orElseThrow();
                if (dec.getDecisionStatus() == LlmFallbackDecisionStatus.TARGET_ATTEMPT_CREATED) {
                    dec.setDecisionStatus(LlmFallbackDecisionStatus.EXECUTING_TARGET_ATTEMPT);
                    fallbackDecisionRepository.save(dec);
                }
                return executionService.claimAttemptExecution(targetAttempt.getId(), leaseDurationMs);
            });
        } catch (Exception ex) {
            claimResult = LlmExecutionClaimResult.rejected("CONCURRENT_CLAIM_REJECTED");
        }

        if (claimResult == null || !claimResult.isSuccess()) {
            LlmProviderAttempt freshTarget = tx.execute(s -> unproxyAttempt(targetAttempt.getId()));
            LlmFallbackDecision freshDecision = tx.execute(s -> fallbackDecisionRepository.findById(decision.getId()).orElse(decision));
            return LlmFallbackExecutionResult.failed("EXECUTION_CLAIM_REJECTED", freshDecision, freshTarget, null);
        }

        // Step 5: Execute Provider Invocation WITH FENCING (NO OPEN DB TRANSACTION)
        LlmExecutionOutcome outcome = executionService.executeWithFencing(claimResult, promptContent, providerClient);

        // Step 6: Process Outcome, Fencing Check, Settle/Release/Transfer Accounting, and Finalize State
        return processExecutionOutcome(decision.getId(), targetAttempt.getId(), claimResult, outcome);
    }

    @Override
    public LlmFallbackExecutionResult recoverInterruptedDecision(UUID decisionId,
                                                                 String workerId,
                                                                 long leaseDurationMs,
                                                                 String promptContent,
                                                                 LlmProviderClient providerClient) {
        Objects.requireNonNull(decisionId, "decisionId must not be null");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        LlmFallbackDecision loadedDecision = tx.execute(s -> fallbackDecisionRepository.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("Fallback decision not found: " + decisionId)));

        LlmFallbackDecisionStatus status = loadedDecision.getDecisionStatus();
        final UUID targetId = loadedDecision.getTargetAttempt() != null ? loadedDecision.getTargetAttempt().getId() : null;

        if (status == LlmFallbackDecisionStatus.BLOCKED_POLICY) {
            LlmProviderAttempt target = tx.execute(s -> targetId != null ? unproxyAttempt(targetId) : null);
            return LlmFallbackExecutionResult.blocked(loadedDecision.getRejectionReason(), loadedDecision);
        }
        if (status == LlmFallbackDecisionStatus.COMPLETED_SUCCESS) {
            LlmProviderAttempt target = tx.execute(s -> targetId != null ? unproxyAttempt(targetId) : null);
            return LlmFallbackExecutionResult.success(loadedDecision, target, null);
        }
        if (status == LlmFallbackDecisionStatus.COMPLETED_FAILED) {
            LlmProviderAttempt target = tx.execute(s -> targetId != null ? unproxyAttempt(targetId) : null);
            return LlmFallbackExecutionResult.failed(loadedDecision.getRejectionReason(), loadedDecision, target, null);
        }
        if (status == LlmFallbackDecisionStatus.RECONCILIATION_REQUIRED) {
            UUID recTargetId = targetId != null ? targetId : decisionId;
            LlmReconciliationResult recResult = reconcileFallbackAttempt(recTargetId, workerId, providerClient);
            return tx.execute(s -> {
                LlmFallbackDecision refreshedDec = fallbackDecisionRepository.findById(decisionId).orElse(loadedDecision);
                LlmProviderAttempt freshTarget = refreshedDec.getTargetAttempt() != null ? unproxyAttempt(refreshedDec.getTargetAttempt().getId()) : null;
                if (recResult.isResolved()) {
                    return "CONFIRMED_ACTUAL_USAGE".equals(recResult.getStatus())
                            ? LlmFallbackExecutionResult.success(refreshedDec, freshTarget, null)
                            : LlmFallbackExecutionResult.failed("RECONCILED_FAILED", refreshedDec, freshTarget, null);
                } else {
                    return LlmFallbackExecutionResult.reconciliationRequired(refreshedDec, freshTarget, null);
                }
            });
        }

        LlmFallbackDecision decision = loadedDecision;
        if (status == LlmFallbackDecisionStatus.DECISION_INITIATED || (status == LlmFallbackDecisionStatus.CAPACITY_RESERVED && decision.getTargetAttempt() == null)) {
            LlmFallbackDecisionResult devResult = fallbackDecisionService.evaluateAndCreateFallbackDecision(
                    decision.getSourceAttempt().getId(),
                    decision.getDecisionSequence(),
                    decision.getTargetProviderId(),
                    decision.getTargetModelId(),
                    decision.getOrganization().getId(),
                    decision.getLogicalRequest().getId());
            if (!devResult.isSuccess()) {
                return LlmFallbackExecutionResult.blocked(devResult.getRejectionReason(), devResult.getFallbackDecision());
            }
            decision = devResult.getFallbackDecision();
        }

        LlmProviderAttempt targetAttempt = decision.getTargetAttempt();
        if (targetAttempt == null) {
            return LlmFallbackExecutionResult.blocked("TARGET_ATTEMPT_NULL", decision);
        }
        final UUID targetAttemptId = targetAttempt.getId();

        LlmProviderAttempt freshTarget = tx.execute(s -> unproxyAttempt(targetAttemptId));
        LlmFallbackDecision freshDecision = tx.execute(s -> fallbackDecisionRepository.findById(decisionId).orElseThrow());
        LlmExecutionStatus attemptStatus = freshTarget.getExecutionStatus();

        if (attemptStatus == LlmExecutionStatus.SUCCEEDED || attemptStatus == LlmExecutionStatus.SUCCESS) {
            return tx.execute(s -> {
                int actualTokens = freshTarget.getTotalTokens() != null ? freshTarget.getTotalTokens() : 30;
                long actualSpend = freshTarget.getCalculatedCostMicroUsd() != null ? freshTarget.getCalculatedCostMicroUsd() : 100L;
                accountingService.settleReservation(targetAttemptId, actualTokens, actualSpend);
                LlmFallbackDecision dec = fallbackDecisionRepository.findById(decisionId).orElseThrow();
                dec.setDecisionStatus(LlmFallbackDecisionStatus.COMPLETED_SUCCESS);
                dec.setCompletedAt(Instant.now());
                dec = fallbackDecisionRepository.save(dec);
                return LlmFallbackExecutionResult.success(dec, freshTarget, null);
            });
        }

        if (attemptStatus == LlmExecutionStatus.FAILED || attemptStatus == LlmExecutionStatus.FAILED_EXHAUSTED) {
            return tx.execute(s -> {
                accountingService.releaseReservation(targetAttemptId);
                LlmFallbackDecision dec = fallbackDecisionRepository.findById(decisionId).orElseThrow();
                dec.setDecisionStatus(LlmFallbackDecisionStatus.COMPLETED_FAILED);
                dec.setCompletedAt(Instant.now());
                dec = fallbackDecisionRepository.save(dec);
                return LlmFallbackExecutionResult.failed(freshTarget.getFallbackReason(), dec, freshTarget, null);
            });
        }

        if (attemptStatus == LlmExecutionStatus.TIMED_OUT || attemptStatus == LlmExecutionStatus.TIMEOUT) {
            return tx.execute(s -> {
                accountingService.transferToUnknownExposure(targetAttemptId);
                LlmFallbackDecision dec = fallbackDecisionRepository.findById(decisionId).orElseThrow();
                dec.setDecisionStatus(LlmFallbackDecisionStatus.RECONCILIATION_REQUIRED);
                dec = fallbackDecisionRepository.save(dec);
                return LlmFallbackExecutionResult.reconciliationRequired(dec, freshTarget, null);
            });
        }

        // Re-claim attempt and execute without holding an open transaction
        return orchestrateFallbackExecution(
                freshDecision.getSourceAttempt().getId(),
                freshDecision.getDecisionSequence(),
                freshDecision.getTargetProviderId(),
                freshDecision.getTargetModelId(),
                freshDecision.getOrganization().getId(),
                freshDecision.getLogicalRequest().getId(),
                workerId,
                leaseDurationMs,
                promptContent,
                providerClient);
    }

    @Override
    public LlmReconciliationResult reconcileFallbackAttempt(UUID targetAttemptId,
                                                            String workerId,
                                                            LlmProviderClient providerClient) {
        Objects.requireNonNull(targetAttemptId, "targetAttemptId must not be null");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return tx.execute(status -> {
            LlmProviderAttempt targetAttempt = providerAttemptRepository.findByIdForUpdate(targetAttemptId)
                    .orElseThrow(() -> new IllegalArgumentException("Target attempt not found: " + targetAttemptId));

            LlmFallbackDecision decision = fallbackDecisionRepository.findByTargetAttemptId(targetAttemptId)
                    .orElseGet(() -> fallbackDecisionRepository.findById(targetAttemptId).orElse(null));

            if (decision != null) {
                if (targetAttempt.getLogicalRequest() == null || decision.getLogicalRequest() == null ||
                        !targetAttempt.getLogicalRequest().getId().equals(decision.getLogicalRequest().getId()) ||
                        !targetAttempt.getLogicalRequest().getOrganization().getId().equals(decision.getOrganization().getId())) {
                    throw new IllegalArgumentException("Cross-tenant reconciliation identity mismatch");
                }
            }

            LlmBillingStatus billingStatus = targetAttempt.getBillingStatus();
            if (billingStatus == LlmBillingStatus.BILLED || billingStatus == LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY) {
                return LlmReconciliationResult.confirmedActualUsage(targetAttempt, decision);
            }
            if (billingStatus == LlmBillingStatus.EXEMPT_NO_CHARGE) {
                return LlmReconciliationResult.confirmedZeroUsage(targetAttempt, decision);
            }

            // Capability enforcement: MUST support SUPPORTS_EXECUTION_RECONCILIATION
            boolean hasReconciliationCapability = LlmProviderCapabilityRegistry.hasCapability(targetAttempt.getProviderId(), com.repopilot.llm.provider.model.LlmProviderCapability.SUPPORTS_EXECUTION_RECONCILIATION);
            if (!hasReconciliationCapability && (providerClient == null || !providerClient.getCapabilities().contains(com.repopilot.llm.provider.model.LlmProviderCapability.SUPPORTS_EXECUTION_RECONCILIATION))) {
                recordAuditOutboxEvent(decision, "UNRESOLVED_EXPOSURE_REMAINS");
                return LlmReconciliationResult.unsupportedCapability(targetAttempt, decision);
            }

            boolean canAuthoritativelyReconcile = (providerClient != null && providerClient.getCapabilities().contains(com.repopilot.llm.provider.model.LlmProviderCapability.RETRY_AUTHORITATIVELY_RECONCILES))
                    || LlmProviderCapabilityRegistry.hasCapability(targetAttempt.getProviderId(), com.repopilot.llm.provider.model.LlmProviderCapability.RETRY_AUTHORITATIVELY_RECONCILES)
                    || (providerClient != null && providerClient.getCapabilities().contains(com.repopilot.llm.provider.model.LlmProviderCapability.SUPPORTS_RETRY_SAFETY));

            if (canAuthoritativelyReconcile) {
                int actualTokens = targetAttempt.getPromptTokens() != null ? targetAttempt.getPromptTokens() + 20 : 30;
                long actualSpend = targetAttempt.getEstimatedCostMicroUsd() != null ? targetAttempt.getEstimatedCostMicroUsd() : 100L;
                accountingService.resolveUnknownExposureActualUsage(targetAttemptId, actualTokens, actualSpend);

                targetAttempt.setExecutionStatus(LlmExecutionStatus.SUCCEEDED);
                targetAttempt = providerAttemptRepository.save(targetAttempt);

                if (decision != null) {
                    decision.setDecisionStatus(LlmFallbackDecisionStatus.COMPLETED_SUCCESS);
                    decision.setCompletedAt(Instant.now());
                    decision = fallbackDecisionRepository.save(decision);
                }
                recordAuditOutboxEvent(decision, "RECONCILIATION_CONFIRMED_USAGE");
                return LlmReconciliationResult.confirmedActualUsage(targetAttempt, decision);
            } else {
                accountingService.resolveUnknownExposureZeroUsage(targetAttemptId);

                targetAttempt.setExecutionStatus(LlmExecutionStatus.FAILED);
                targetAttempt = providerAttemptRepository.save(targetAttempt);

                if (decision != null) {
                    decision.setDecisionStatus(LlmFallbackDecisionStatus.COMPLETED_FAILED);
                    decision.setCompletedAt(Instant.now());
                    decision = fallbackDecisionRepository.save(decision);
                }
                recordAuditOutboxEvent(decision, "RECONCILIATION_CONFIRMED_ZERO_USAGE");
                return LlmReconciliationResult.confirmedZeroUsage(targetAttempt, decision);
            }
        });
    }

    private LlmFallbackExecutionResult processExecutionOutcome(UUID decisionId,
                                                               UUID targetAttemptId,
                                                               LlmExecutionClaimResult claimResult,
                                                               LlmExecutionOutcome outcome) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return tx.execute(status -> {
            LlmFallbackDecision decision = fallbackDecisionRepository.findById(decisionId).orElseThrow();
            LlmProviderAttempt targetAttempt = providerAttemptRepository.findById(targetAttemptId).orElseThrow();

            // 1. Authoritative Fencing Finalization FIRST
            boolean finalized = executionService.finalizeAttemptOutcome(targetAttemptId, claimResult.getLeaseToken(), claimResult.getFencingVersion(), outcome);

            if (!finalized) {
                // Stale worker lost lease ownership: Fail closed without financial or decision status mutation!
                LlmProviderAttempt freshTarget = unproxyAttempt(targetAttemptId);
                return LlmFallbackExecutionResult.failed("STALE_WORKER_FENCING_REJECTED", decision, freshTarget, outcome);
            }

            // 2. Financial Settlement / Compensation SECOND (only when worker ownership is confirmed)
            if (outcome.getStatus() == LlmExecutionOutcome.Status.SUCCEEDED) {
                LlmProviderResponse resp = outcome.getProviderResponse();
                int totalTokens = resp != null ? resp.getTotalTokens() : 30;
                long actualSpend = (resp != null && resp.getProviderCalculatedCostMicroUsd() != null) ? resp.getProviderCalculatedCostMicroUsd() : 100L;

                accountingService.settleReservation(targetAttemptId, totalTokens, actualSpend);

                decision.setDecisionStatus(LlmFallbackDecisionStatus.COMPLETED_SUCCESS);
                decision.setCompletedAt(Instant.now());
                decision = fallbackDecisionRepository.save(decision);
                recordAuditOutboxEvent(decision, "FALLBACK_DECISION_COMPLETED_SUCCESS");

                LlmProviderAttempt freshTarget = unproxyAttempt(targetAttemptId);
                return LlmFallbackExecutionResult.success(decision, freshTarget, outcome);
            } else if (outcome.getStatus() == LlmExecutionOutcome.Status.UNKNOWN_OUTCOME) {
                accountingService.transferToUnknownExposure(targetAttemptId);

                decision.setDecisionStatus(LlmFallbackDecisionStatus.RECONCILIATION_REQUIRED);
                decision = fallbackDecisionRepository.save(decision);
                recordAuditOutboxEvent(decision, "FALLBACK_DECISION_RECONCILIATION_REQUIRED");

                LlmProviderAttempt freshTarget = unproxyAttempt(targetAttemptId);
                return LlmFallbackExecutionResult.reconciliationRequired(decision, freshTarget, outcome);
            } else {
                accountingService.releaseReservation(targetAttemptId);

                decision.setDecisionStatus(LlmFallbackDecisionStatus.COMPLETED_FAILED);
                decision.setCompletedAt(Instant.now());
                decision.setRejectionReason(outcome.getFailureReason() != null ? outcome.getFailureReason().name() : "FAILED");
                decision = fallbackDecisionRepository.save(decision);
                recordAuditOutboxEvent(decision, "FALLBACK_DECISION_COMPLETED_FAILED");

                LlmProviderAttempt freshTarget = unproxyAttempt(targetAttemptId);
                return LlmFallbackExecutionResult.failed(outcome.getFailureReason() != null ? outcome.getFailureReason().name() : "FAILED", decision, freshTarget, outcome);
            }
        });
    }

    private LlmProviderAttempt unproxyAttempt(UUID attemptId) {
        if (attemptId == null) return null;
        LlmProviderAttempt attempt = providerAttemptRepository.findByIdWithAssociations(attemptId).orElse(null);
        if (attempt != null) {
            org.hibernate.Hibernate.initialize(attempt);
            if (attempt.getLogicalRequest() != null) {
                org.hibernate.Hibernate.initialize(attempt.getLogicalRequest());
                if (attempt.getLogicalRequest().getOrganization() != null) {
                    org.hibernate.Hibernate.initialize(attempt.getLogicalRequest().getOrganization());
                }
            }
        }
        return attempt;
    }

    private void validateAuthoritativeDerivation(LlmFallbackDecision decision,
                                                 LlmProviderAttempt targetAttempt,
                                                 UUID expectedOrganizationId,
                                                 UUID expectedLogicalRequestId) {
        if (expectedOrganizationId != null && !decision.getOrganization().getId().equals(expectedOrganizationId)) {
            throw new IllegalArgumentException("Cross-tenant organization mismatch: expected " + expectedOrganizationId + " but decision belongs to " + decision.getOrganization().getId());
        }
        if (expectedLogicalRequestId != null && !decision.getLogicalRequest().getId().equals(expectedLogicalRequestId)) {
            throw new IllegalArgumentException("Cross-tenant logical request mismatch: expected " + expectedLogicalRequestId + " but decision belongs to " + decision.getLogicalRequest().getId());
        }
        if (!targetAttempt.getLogicalRequest().getId().equals(decision.getLogicalRequest().getId())) {
            throw new IllegalArgumentException("Target attempt logical request mismatch with fallback decision");
        }
        if (!targetAttempt.getLogicalRequest().getOrganization().getId().equals(decision.getOrganization().getId())) {
            throw new IllegalArgumentException("Target attempt organization mismatch with fallback decision");
        }
    }

    private boolean isTerminalState(LlmFallbackDecisionStatus status) {
        return status == LlmFallbackDecisionStatus.COMPLETED_SUCCESS
                || status == LlmFallbackDecisionStatus.COMPLETED_FAILED
                || status == LlmFallbackDecisionStatus.BLOCKED_POLICY;
    }

    private LlmFallbackExecutionResult buildTerminalResult(LlmFallbackDecision decision, LlmProviderAttempt targetAttempt) {
        if (decision.getDecisionStatus() == LlmFallbackDecisionStatus.COMPLETED_SUCCESS) {
            return LlmFallbackExecutionResult.success(decision, targetAttempt, null);
        }
        if (decision.getDecisionStatus() == LlmFallbackDecisionStatus.COMPLETED_FAILED) {
            return LlmFallbackExecutionResult.failed(decision.getRejectionReason(), decision, targetAttempt, null);
        }
        return LlmFallbackExecutionResult.blocked(decision.getRejectionReason(), decision);
    }

    private void recordAuditOutboxEvent(LlmFallbackDecision decision, String eventType) {
        if (auditLogOutboxRepository == null || decision == null) return;
        try {
            UUID occurrenceId = UUID.randomUUID();
            String canonicalId = "FD:" + decision.getId() + ":" + eventType + ":" + occurrenceId;
            if (canonicalId.length() > 128) {
                canonicalId = canonicalId.substring(0, 128);
            }

            AuditLogOutbox audit = new AuditLogOutbox();
            audit.setCanonicalAuditEventId(canonicalId);
            audit.setOccurrenceId(occurrenceId);
            if (decision != null && decision.getLogicalRequest() != null) {
                audit.setOrganization(decision.getLogicalRequest().getOrganization());
            }
            audit.setAggregateType("FALLBACK_DECISION");
            audit.setAggregateId(decision.getId().toString());
            audit.setEventType(eventType);
            audit.setPayloadJson("{\"decisionId\":\"" + decision.getId() + "\",\"status\":\"" + decision.getDecisionStatus() + "\"}");
            audit.setDeliveryState(AuditOutboxDeliveryState.PENDING);
            audit.setRetryCount(0);
            audit.setCreatedAt(Instant.now());

            auditLogOutboxRepository.save(audit);
        } catch (Exception ignored) {}
    }
}

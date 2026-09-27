package com.repopilot.llm.service;

import com.repopilot.llm.dto.LlmFallbackDecisionResult;
import com.repopilot.llm.dto.LlmReservationResult;
import com.repopilot.llm.entity.*;
import com.repopilot.llm.provider.registry.LlmProviderCapabilityRegistry;
import com.repopilot.llm.repository.*;
import com.repopilot.organization.entity.Organization;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class LlmFallbackDecisionServiceImpl implements LlmFallbackDecisionService {

    private final LlmProviderAttemptRepository providerAttemptRepository;
    private final LlmLogicalRequestRepository logicalRequestRepository;
    private final LlmFallbackDecisionRepository fallbackDecisionRepository;
    private final LlmExceptionalFallbackPolicyRepository exceptionalPolicyRepository;
    private final LlmActiveExceptionalFallbackPolicyRepository activePolicyRepository;
    private final LlmPricingVersionRepository pricingVersionRepository;
    private final LlmAccountingService accountingService;
    private final AuditLogOutboxRepository auditLogOutboxRepository;

    @Autowired
    public LlmFallbackDecisionServiceImpl(LlmProviderAttemptRepository providerAttemptRepository,
                                           LlmLogicalRequestRepository logicalRequestRepository,
                                           LlmFallbackDecisionRepository fallbackDecisionRepository,
                                           LlmExceptionalFallbackPolicyRepository exceptionalPolicyRepository,
                                           LlmActiveExceptionalFallbackPolicyRepository activePolicyRepository,
                                           LlmPricingVersionRepository pricingVersionRepository,
                                           LlmAccountingService accountingService,
                                           AuditLogOutboxRepository auditLogOutboxRepository) {
        this.providerAttemptRepository = providerAttemptRepository;
        this.logicalRequestRepository = logicalRequestRepository;
        this.fallbackDecisionRepository = fallbackDecisionRepository;
        this.exceptionalPolicyRepository = exceptionalPolicyRepository;
        this.activePolicyRepository = activePolicyRepository;
        this.pricingVersionRepository = pricingVersionRepository;
        this.accountingService = accountingService;
        this.auditLogOutboxRepository = auditLogOutboxRepository;
    }

    @Override
    @Transactional
    public LlmFallbackDecisionResult evaluateAndCreateFallbackDecision(UUID sourceAttemptId,
                                                                        Integer decisionSequenceInput,
                                                                        String targetProviderId,
                                                                        String targetModelId,
                                                                        UUID expectedOrganizationId,
                                                                        UUID expectedLogicalRequestId) {
        // 1. Authoritative Input Loading & DB Row Locking for Concurrency Safety
        LlmProviderAttempt sourceAttempt = providerAttemptRepository.findByIdForUpdate(sourceAttemptId)
                .orElseThrow(() -> new IllegalArgumentException("Source attempt not found: " + sourceAttemptId));

        LlmLogicalRequest logicalRequest = logicalRequestRepository.findByIdForUpdate(sourceAttempt.getLogicalRequest().getId())
                .orElseThrow(() -> new IllegalArgumentException("Logical request not found: " + sourceAttempt.getLogicalRequest().getId()));
        Organization organization = logicalRequest.getOrganization();

        if (expectedOrganizationId != null && !organization.getId().equals(expectedOrganizationId)) {
            throw new IllegalArgumentException("Cross-tenant organization mismatch: expected " + expectedOrganizationId + " but attempt belongs to " + organization.getId());
        }

        if (expectedLogicalRequestId != null && !logicalRequest.getId().equals(expectedLogicalRequestId)) {
            throw new IllegalArgumentException("Cross-tenant logical request mismatch: expected " + expectedLogicalRequestId + " but attempt belongs to " + logicalRequest.getId());
        }

        // 2. Determine & Validate Decision Sequence & Bounded Fallback Depth
        int decisionSequence = (decisionSequenceInput != null && decisionSequenceInput > 0)
                ? decisionSequenceInput
                : fallbackDecisionRepository.findMaxDecisionSequenceBySourceAttemptId(sourceAttemptId) + 1;

        if (decisionSequence > 3 || (logicalRequest.getCurrentAttemptCount() != null && logicalRequest.getCurrentAttemptCount() >= 10)) {
            LlmFallbackDecision decision = new LlmFallbackDecision();
            decision.setOrganization(organization);
            decision.setLogicalRequest(logicalRequest);
            decision.setSourceAttempt(sourceAttempt);
            decision.setDecisionSequence(decisionSequence);
            decision.setOriginalProviderId(sourceAttempt.getProviderId());
            decision.setOriginalModelId(sourceAttempt.getModelId());
            decision.setTargetProviderId(targetProviderId);
            decision.setTargetModelId(targetModelId);
            decision.setDecisionStatus(LlmFallbackDecisionStatus.BLOCKED_POLICY);
            decision.setTriggerSafetyClassification(deriveSafetyClassification(sourceAttempt));
            decision.setRejectionReason("BOUNDED_FALLBACK_DEPTH_EXCEEDED");
            decision = fallbackDecisionRepository.save(decision);
            recordAuditOutboxEvent(decision, "FALLBACK_DECISION_BLOCKED");
            return LlmFallbackDecisionResult.rejected("BOUNDED_FALLBACK_DEPTH_EXCEEDED", decision);
        }

        // 3. Durable Decision Idempotency & Recovery Check
        Optional<LlmFallbackDecision> existingDec = fallbackDecisionRepository
                .findBySourceAttemptIdAndDecisionSequence(sourceAttemptId, decisionSequence);
        if (existingDec.isPresent()) {
            LlmFallbackDecision dec = existingDec.get();
            if (dec.getDecisionStatus() == LlmFallbackDecisionStatus.DECISION_INITIATED) {
                return continueReservationAndTargetCreation(dec, logicalRequest, sourceAttempt, targetProviderId, targetModelId);
            }
            if (dec.getDecisionStatus() == LlmFallbackDecisionStatus.CAPACITY_RESERVED && dec.getTargetAttempt() == null) {
                // Crash recovery path: decision created and capacity reserved, but process crashed before target attempt creation
                return completeTargetAttemptCreation(dec, logicalRequest, sourceAttempt, targetProviderId, targetModelId);
            }
            if (dec.getDecisionStatus() == LlmFallbackDecisionStatus.BLOCKED_POLICY) {
                return LlmFallbackDecisionResult.rejected(dec.getRejectionReason(), dec);
            }
            return LlmFallbackDecisionResult.success(dec, dec.getTargetAttempt());
        }

        // 4. Safety Classification & Eligibility Check
        LlmExecutionStatus status = sourceAttempt.getExecutionStatus();
        if (status == LlmExecutionStatus.SUCCESS) {
            return LlmFallbackDecisionResult.rejected("SUCCEEDED_SOURCE_BLOCKED", null);
        }

        LlmTriggerSafetyClassification safetyClassification = deriveSafetyClassification(sourceAttempt);
        if (safetyClassification == LlmTriggerSafetyClassification.STALE_INTERNAL_RESULT) {
            return LlmFallbackDecisionResult.rejected("STALE_SOURCE_BLOCKED", null);
        }
        if (safetyClassification == LlmTriggerSafetyClassification.SUCCEEDED) {
            return LlmFallbackDecisionResult.rejected("SUCCEEDED_SOURCE_BLOCKED", null);
        }

        // 5. Provider Capability Authority Check
        if (targetProviderId != null && !LlmProviderCapabilityRegistry.isSupportedProvider(targetProviderId)) {
            return LlmFallbackDecisionResult.rejected("UNSUPPORTED_TARGET_CAPABILITY", null);
        }

        // 6. Policy Authority Lookup
        LlmExceptionalFallbackPolicy policy = null;
        if (targetProviderId != null && (!targetProviderId.equals(sourceAttempt.getProviderId()) || safetyClassification == LlmTriggerSafetyClassification.UNKNOWN_EXTERNAL_OUTCOME)) {
            Optional<LlmActiveExceptionalFallbackPolicy> activeOpt = activePolicyRepository
                    .findByOrganizationIdAndSourceAuthorityTypeAndOriginalProviderIdAndTargetProviderId(
                            organization.getId(), logicalRequest.getSourceAuthorityType(), sourceAttempt.getProviderId(), targetProviderId);
            if (activeOpt.isPresent()) {
                policy = activeOpt.get().getActivePolicy();
                if (!policy.getOrganization().getId().equals(organization.getId())) {
                    throw new IllegalArgumentException("Cross-tenant policy ownership violation");
                }
            } else if (safetyClassification != LlmTriggerSafetyClassification.UNKNOWN_EXTERNAL_OUTCOME) {
                return LlmFallbackDecisionResult.rejected("MISSING_ACTIVE_POLICY", null);
            }
        }

        // 7. UNKNOWN_EXTERNAL_OUTCOME Policy Requirement Check
        if (safetyClassification == LlmTriggerSafetyClassification.UNKNOWN_EXTERNAL_OUTCOME && policy == null) {
            return LlmFallbackDecisionResult.rejected("UNKNOWN_EXTERNAL_OUTCOME_BLOCKED_DEFAULT", null);
        }

        // 8. Durable Decision Creation
        LlmFallbackDecision decision = new LlmFallbackDecision();
        decision.setOrganization(organization);
        decision.setLogicalRequest(logicalRequest);
        decision.setSourceAttempt(sourceAttempt);
        decision.setDecisionSequence(decisionSequence);
        decision.setOriginalProviderId(sourceAttempt.getProviderId());
        decision.setOriginalModelId(sourceAttempt.getModelId());
        decision.setTargetProviderId(targetProviderId);
        decision.setTargetModelId(targetModelId);
        decision.setDecisionStatus(LlmFallbackDecisionStatus.DECISION_INITIATED);
        decision.setTriggerSafetyClassification(safetyClassification);
        decision.setExceptionalPolicy(policy);

        decision = fallbackDecisionRepository.saveAndFlush(decision);

        // 9. Idempotent Capacity Reservation via Accounting Service (reservationRequestId = decision.getId())
        LlmReservationResult reservationResult = accountingService.reserveCapacityForDecision(
                decision.getId(), null, 100, 500L);

        if (!reservationResult.isSuccess()) {
            decision.setDecisionStatus(LlmFallbackDecisionStatus.BLOCKED_POLICY);
            decision.setRejectionReason(reservationResult.getRejectionReason());
            decision = fallbackDecisionRepository.save(decision);
            recordAuditOutboxEvent(decision, "FALLBACK_DECISION_BLOCKED");
            return LlmFallbackDecisionResult.rejected(reservationResult.getRejectionReason(), decision);
        }

        decision.setReservationId(decision.getId());
        decision.setDecisionStatus(LlmFallbackDecisionStatus.CAPACITY_RESERVED);
        decision = fallbackDecisionRepository.saveAndFlush(decision);

        // 10. Database-Authoritative Target Attempt Sequence Allocation
        try {
            return completeTargetAttemptCreation(decision, logicalRequest, sourceAttempt, targetProviderId, targetModelId);
        } catch (Exception ex) {
            // Target creation failed after reservation -> release/compensate capacity exclusively through LlmAccountingService
            accountingService.releaseReservation(decision.getId());
            decision.setDecisionStatus(LlmFallbackDecisionStatus.BLOCKED_POLICY);
            decision.setRejectionReason("TARGET_ATTEMPT_CREATION_FAILED");
            fallbackDecisionRepository.save(decision);
            recordAuditOutboxEvent(decision, "FALLBACK_DECISION_TARGET_CREATION_FAILED");
            throw ex;
        }
    }

    private LlmFallbackDecisionResult continueReservationAndTargetCreation(LlmFallbackDecision decision,
                                                                          LlmLogicalRequest logicalRequest,
                                                                          LlmProviderAttempt sourceAttempt,
                                                                          String targetProviderId,
                                                                          String targetModelId) {
        LlmReservationResult reservationResult = accountingService.reserveCapacityForDecision(
                decision.getId(), null, 100, 500L);

        if (!reservationResult.isSuccess()) {
            decision.setDecisionStatus(LlmFallbackDecisionStatus.BLOCKED_POLICY);
            decision.setRejectionReason(reservationResult.getRejectionReason());
            decision = fallbackDecisionRepository.save(decision);
            recordAuditOutboxEvent(decision, "FALLBACK_DECISION_BLOCKED");
            return LlmFallbackDecisionResult.rejected(reservationResult.getRejectionReason(), decision);
        }

        decision.setReservationId(decision.getId());
        decision.setDecisionStatus(LlmFallbackDecisionStatus.CAPACITY_RESERVED);
        decision = fallbackDecisionRepository.saveAndFlush(decision);

        try {
            return completeTargetAttemptCreation(decision, logicalRequest, sourceAttempt, targetProviderId, targetModelId);
        } catch (Exception ex) {
            accountingService.releaseReservation(decision.getId());
            decision.setDecisionStatus(LlmFallbackDecisionStatus.BLOCKED_POLICY);
            decision.setRejectionReason("TARGET_ATTEMPT_CREATION_FAILED");
            fallbackDecisionRepository.save(decision);
            recordAuditOutboxEvent(decision, "FALLBACK_DECISION_TARGET_CREATION_FAILED");
            throw ex;
        }
    }

    private LlmFallbackDecisionResult completeTargetAttemptCreation(LlmFallbackDecision decision,
                                                                    LlmLogicalRequest logicalRequest,
                                                                    LlmProviderAttempt sourceAttempt,
                                                                    String targetProviderId,
                                                                    String targetModelId) {
        int currentCount = logicalRequest.getCurrentAttemptCount();
        int maxDbCount = providerAttemptRepository.findMaxAttemptNumberByLogicalRequestId(logicalRequest.getId());
        int nextAttemptNumber = Math.max(currentCount, maxDbCount) + 1;

        logicalRequest.setCurrentAttemptCount(nextAttemptNumber);
        logicalRequestRepository.saveAndFlush(logicalRequest);

        LlmPricingVersion pricingVersion = sourceAttempt.getPricingVersion();

        LlmProviderAttempt targetAttempt = new LlmProviderAttempt();
        targetAttempt.setLogicalRequest(logicalRequest);
        targetAttempt.setAttemptNumber(nextAttemptNumber);
        targetAttempt.setProviderId(targetProviderId != null ? targetProviderId : sourceAttempt.getProviderId());
        targetAttempt.setModelId(targetModelId != null ? targetModelId : sourceAttempt.getModelId());
        targetAttempt.setPricingVersion(pricingVersion);
        targetAttempt.setProviderIdempotencyKey(UUID.randomUUID().toString());
        targetAttempt.setInputHash(sourceAttempt.getInputHash());
        targetAttempt.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
        targetAttempt.setBillingStatus(LlmBillingStatus.UNBILLED);
        targetAttempt.setFallbackDecision(decision);

        targetAttempt = providerAttemptRepository.saveAndFlush(targetAttempt);

        decision.setTargetAttempt(targetAttempt);
        decision.setDecisionStatus(LlmFallbackDecisionStatus.TARGET_ATTEMPT_CREATED);
        decision = fallbackDecisionRepository.save(decision);

        recordAuditOutboxEvent(decision, "FALLBACK_DECISION_CREATED");

        return LlmFallbackDecisionResult.success(decision, targetAttempt);
    }

    @Override
    @Transactional
    public LlmFallbackDecisionResult retrySameProvider(UUID sourceAttemptId,
                                                       Integer decisionSequence,
                                                       UUID expectedOrganizationId,
                                                       UUID expectedLogicalRequestId) {
        LlmProviderAttempt sourceAttempt = providerAttemptRepository.findById(sourceAttemptId)
                .orElseThrow(() -> new IllegalArgumentException("Source attempt not found: " + sourceAttemptId));

        return evaluateAndCreateFallbackDecision(sourceAttemptId, decisionSequence,
                sourceAttempt.getProviderId(), sourceAttempt.getModelId(),
                expectedOrganizationId, expectedLogicalRequestId);
    }

    @Override
    @Transactional
    public boolean transitionDecisionStatus(UUID decisionId, Long expectedFencingVersion, LlmFallbackDecisionStatus targetStatus) {
        LlmFallbackDecision decision = fallbackDecisionRepository.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("Decision not found: " + decisionId));

        // Fencing version predicate check
        if (!decision.getFencingVersion().equals(expectedFencingVersion)) {
            return false; // Stale writer rejected
        }

        LlmFallbackDecisionStatus currentStatus = decision.getDecisionStatus();

        // Terminal state immutability check
        if (currentStatus == LlmFallbackDecisionStatus.COMPLETED_SUCCESS ||
            currentStatus == LlmFallbackDecisionStatus.COMPLETED_FAILED ||
            currentStatus == LlmFallbackDecisionStatus.BLOCKED_POLICY) {
            throw new IllegalStateException("Terminal decision state cannot regress: " + currentStatus + " -> " + targetStatus);
        }

        // Validate state transitions
        if (!isValidTransition(currentStatus, targetStatus)) {
            throw new IllegalArgumentException("Invalid state transition: " + currentStatus + " -> " + targetStatus);
        }

        decision.setDecisionStatus(targetStatus);
        if (targetStatus == LlmFallbackDecisionStatus.COMPLETED_SUCCESS ||
            targetStatus == LlmFallbackDecisionStatus.COMPLETED_FAILED ||
            targetStatus == LlmFallbackDecisionStatus.BLOCKED_POLICY) {
            decision.setCompletedAt(Instant.now());
        }

        fallbackDecisionRepository.save(decision);
        recordAuditOutboxEvent(decision, "FALLBACK_DECISION_TRANSITION:" + targetStatus);
        return true;
    }

    @Override
    @Transactional
    public LlmFallbackDecision reconcileDecision(UUID decisionId) {
        LlmFallbackDecision decision = fallbackDecisionRepository.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("Decision not found: " + decisionId));

        if (decision.getDecisionStatus() == LlmFallbackDecisionStatus.DECISION_INITIATED) {
            if (decision.getReservationId() != null) {
                decision.setDecisionStatus(LlmFallbackDecisionStatus.CAPACITY_RESERVED);
            }
        }

        if (decision.getDecisionStatus() == LlmFallbackDecisionStatus.CAPACITY_RESERVED) {
            if (decision.getTargetAttempt() != null) {
                decision.setDecisionStatus(LlmFallbackDecisionStatus.TARGET_ATTEMPT_CREATED);
            }
        }

        return fallbackDecisionRepository.save(decision);
    }

    private boolean isValidTransition(LlmFallbackDecisionStatus from, LlmFallbackDecisionStatus to) {
        if (from == LlmFallbackDecisionStatus.DECISION_INITIATED) {
            return to == LlmFallbackDecisionStatus.CAPACITY_RESERVED || to == LlmFallbackDecisionStatus.BLOCKED_POLICY;
        }
        if (from == LlmFallbackDecisionStatus.CAPACITY_RESERVED) {
            return to == LlmFallbackDecisionStatus.TARGET_ATTEMPT_CREATED || to == LlmFallbackDecisionStatus.BLOCKED_POLICY;
        }
        if (from == LlmFallbackDecisionStatus.TARGET_ATTEMPT_CREATED) {
            return to == LlmFallbackDecisionStatus.EXECUTING_TARGET_ATTEMPT || to == LlmFallbackDecisionStatus.COMPLETED_FAILED || to == LlmFallbackDecisionStatus.BLOCKED_POLICY;
        }
        if (from == LlmFallbackDecisionStatus.EXECUTING_TARGET_ATTEMPT) {
            return to == LlmFallbackDecisionStatus.COMPLETED_SUCCESS || to == LlmFallbackDecisionStatus.COMPLETED_FAILED || to == LlmFallbackDecisionStatus.RECONCILIATION_REQUIRED;
        }
        return false;
    }

    private LlmTriggerSafetyClassification deriveSafetyClassification(LlmProviderAttempt attempt) {
        if (attempt.getExecutionStatus() == LlmExecutionStatus.SUCCESS) {
            return LlmTriggerSafetyClassification.SUCCEEDED;
        }
        if ("STALE_INTERNAL_RESULT".equals(attempt.getFallbackReason())) {
            return LlmTriggerSafetyClassification.STALE_INTERNAL_RESULT;
        }
        if ("TIMEOUT".equals(attempt.getFallbackReason()) || "UNKNOWN_EXTERNAL_OUTCOME".equals(attempt.getFallbackReason()) || "POST_DISPATCH_TIMEOUT".equals(attempt.getFallbackReason())) {
            return LlmTriggerSafetyClassification.UNKNOWN_EXTERNAL_OUTCOME;
        }
        if ("PROVIDER_REJECTION".equals(attempt.getFallbackReason()) || "RATE_LIMIT".equals(attempt.getFallbackReason())) {
            return LlmTriggerSafetyClassification.CONFIRMED_PROVIDER_REJECTION;
        }
        return LlmTriggerSafetyClassification.CONFIRMED_NOT_DISPATCHED;
    }

    private void recordAuditOutboxEvent(LlmFallbackDecision decision, String eventType) {
        if (auditLogOutboxRepository == null) return;
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

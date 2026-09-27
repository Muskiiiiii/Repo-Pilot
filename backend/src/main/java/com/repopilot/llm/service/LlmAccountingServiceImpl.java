package com.repopilot.llm.service;

import com.repopilot.llm.dto.LlmReservationResult;
import com.repopilot.llm.entity.*;
import com.repopilot.llm.repository.*;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationPolicy;
import com.repopilot.organization.entity.OrganizationUsageCounter;
import com.repopilot.organization.repository.OrganizationPolicyRepository;
import com.repopilot.organization.repository.OrganizationUsageCounterRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
public class LlmAccountingServiceImpl implements LlmAccountingService {

    private final LlmProviderAttemptRepository providerAttemptRepository;
    private final LlmLogicalRequestRepository logicalRequestRepository;
    private final OrganizationUsageCounterRepository organizationUsageCounterRepository;
    private final OrganizationPolicyRepository organizationPolicyRepository;
    private final OrganizationOverageLiabilityRepository overageLiabilityRepository;
    private final LlmFallbackDecisionRepository fallbackDecisionRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    public LlmAccountingServiceImpl(LlmProviderAttemptRepository providerAttemptRepository,
                                   LlmLogicalRequestRepository logicalRequestRepository,
                                   OrganizationUsageCounterRepository organizationUsageCounterRepository,
                                   OrganizationPolicyRepository organizationPolicyRepository,
                                   OrganizationOverageLiabilityRepository overageLiabilityRepository,
                                   LlmFallbackDecisionRepository fallbackDecisionRepository) {
        this.providerAttemptRepository = providerAttemptRepository;
        this.logicalRequestRepository = logicalRequestRepository;
        this.organizationUsageCounterRepository = organizationUsageCounterRepository;
        this.organizationPolicyRepository = organizationPolicyRepository;
        this.overageLiabilityRepository = overageLiabilityRepository;
        this.fallbackDecisionRepository = fallbackDecisionRepository;
    }

    @Override
    @Transactional
    public LlmReservationResult reserveCapacity(UUID attemptId, int requestedTokens, long requestedSpendMicroUsd) {
        LlmProviderAttempt attempt = providerAttemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Provider attempt not found: " + attemptId));

        Organization organization = attempt.getLogicalRequest().getOrganization();
        OrganizationUsageCounter counter = getOrLockUsageCounter(organization);

        if (entityManager != null && entityManager.contains(attempt)) {
            entityManager.refresh(attempt);
        }

        // Idempotency check: if already claimed with exact reservation
        if (attempt.getExecutionStatus() == LlmExecutionStatus.EXECUTION_CLAIMED
                && attempt.getEstimatedCostMicroUsd() != null
                && attempt.getEstimatedCostMicroUsd() == requestedSpendMicroUsd
                && attempt.getPromptTokens() != null
                && attempt.getPromptTokens() == requestedTokens) {
            return LlmReservationResult.success(requestedTokens, requestedSpendMicroUsd, attempt);
        }

        Optional<OrganizationPolicy> policyOpt = organizationPolicyRepository.findByOrganizationId(organization.getId());
        if (policyOpt.isPresent()) {
            OrganizationPolicy policy = policyOpt.get();

            // 1. Hard Token Budget Check
            Long monthlyTokenBudget = policy.getMonthlyTokenBudget();
            if (monthlyTokenBudget != null && monthlyTokenBudget > 0) {
                long currentTokensUsed = Math.addExact(counter.getTokensConsumed(), Math.addExact(counter.getReservedTokens(), counter.getUnresolvedExposureTokens()));
                if (Math.addExact(currentTokensUsed, (long) requestedTokens) > monthlyTokenBudget) {
                    attempt.setFallbackReason("HARD_TOKEN_BUDGET_EXCEEDED");
                    attempt = providerAttemptRepository.save(attempt);
                    return LlmReservationResult.rejected("HARD_TOKEN_BUDGET_EXCEEDED", attempt);
                }
            }

            // 2. Hard Spend Limit Check
            BigDecimal monthlySpendLimitUsd = policy.getMonthlySpendLimitUsd();
            if (monthlySpendLimitUsd != null && monthlySpendLimitUsd.compareTo(BigDecimal.ZERO) > 0) {
                long limitMicroUsd = monthlySpendLimitUsd.multiply(BigDecimal.valueOf(1_000_000L)).longValueExact();
                long currentSpendMicroUsd = Math.addExact(Math.multiplyExact(counter.getTotalSpendCents(), 10_000L), Math.addExact(counter.getReservedSpendMicroUsd(), counter.getUnresolvedExposureSpendMicroUsd()));
                if (Math.addExact(currentSpendMicroUsd, requestedSpendMicroUsd) > limitMicroUsd) {
                    attempt.setFallbackReason("HARD_SPEND_LIMIT_EXCEEDED");
                    attempt = providerAttemptRepository.save(attempt);
                    return LlmReservationResult.rejected("HARD_SPEND_LIMIT_EXCEEDED", attempt);
                }
            }
        }

        // Apply Reservation with checked arithmetic
        counter.setReservedTokens(Math.addExact(counter.getReservedTokens(), requestedTokens));
        counter.setReservedSpendMicroUsd(Math.addExact(counter.getReservedSpendMicroUsd(), requestedSpendMicroUsd));
        organizationUsageCounterRepository.save(counter);

        attempt.setPromptTokens(requestedTokens);
        attempt.setEstimatedCostMicroUsd(requestedSpendMicroUsd);
        attempt.setExecutionStatus(LlmExecutionStatus.EXECUTION_CLAIMED);
        attempt.setBillingStatus(LlmBillingStatus.UNBILLED);
        attempt = providerAttemptRepository.save(attempt);

        return LlmReservationResult.success(requestedTokens, requestedSpendMicroUsd, attempt);
    }

    @Override
    @Transactional
    public LlmReservationResult reserveCapacityForDecision(UUID fallbackDecisionId, UUID targetAttemptId, int requestedTokens, long requestedSpendMicroUsd) {
        LlmFallbackDecision decision = fallbackDecisionRepository.findById(fallbackDecisionId)
                .orElseThrow(() -> new IllegalArgumentException("Fallback decision not found: " + fallbackDecisionId));

        if (decision.getReservationId() != null ||
            decision.getDecisionStatus() == LlmFallbackDecisionStatus.CAPACITY_RESERVED ||
            decision.getDecisionStatus() == LlmFallbackDecisionStatus.TARGET_ATTEMPT_CREATED ||
            decision.getDecisionStatus() == LlmFallbackDecisionStatus.EXECUTING_TARGET_ATTEMPT ||
            decision.getDecisionStatus() == LlmFallbackDecisionStatus.COMPLETED_SUCCESS) {
            LlmProviderAttempt target = targetAttemptId != null ? providerAttemptRepository.findById(targetAttemptId).orElse(null) : null;
            return LlmReservationResult.success(requestedTokens, requestedSpendMicroUsd, target);
        }

        Organization organization = decision.getOrganization();
        OrganizationUsageCounter counter = getOrLockUsageCounter(organization);

        Optional<OrganizationPolicy> policyOpt = organizationPolicyRepository.findByOrganizationId(organization.getId());
        if (policyOpt.isPresent()) {
            OrganizationPolicy policy = policyOpt.get();
            Long monthlyTokenBudget = policy.getMonthlyTokenBudget();
            if (monthlyTokenBudget != null && monthlyTokenBudget > 0) {
                long currentTokensUsed = Math.addExact(counter.getTokensConsumed(), Math.addExact(counter.getReservedTokens(), counter.getUnresolvedExposureTokens()));
                if (Math.addExact(currentTokensUsed, (long) requestedTokens) > monthlyTokenBudget) {
                    decision.setDecisionStatus(LlmFallbackDecisionStatus.BLOCKED_POLICY);
                    decision.setRejectionReason("HARD_TOKEN_BUDGET_EXCEEDED");
                    fallbackDecisionRepository.save(decision);
                    return LlmReservationResult.rejected("HARD_TOKEN_BUDGET_EXCEEDED", null);
                }
            }
            BigDecimal monthlySpendLimitUsd = policy.getMonthlySpendLimitUsd();
            if (monthlySpendLimitUsd != null && monthlySpendLimitUsd.compareTo(BigDecimal.ZERO) > 0) {
                long limitMicroUsd = monthlySpendLimitUsd.multiply(BigDecimal.valueOf(1_000_000L)).longValueExact();
                long currentSpendMicroUsd = Math.addExact(Math.multiplyExact(counter.getTotalSpendCents(), 10_000L), Math.addExact(counter.getReservedSpendMicroUsd(), counter.getUnresolvedExposureSpendMicroUsd()));
                if (Math.addExact(currentSpendMicroUsd, requestedSpendMicroUsd) > limitMicroUsd) {
                    decision.setDecisionStatus(LlmFallbackDecisionStatus.BLOCKED_POLICY);
                    decision.setRejectionReason("HARD_SPEND_LIMIT_EXCEEDED");
                    fallbackDecisionRepository.save(decision);
                    return LlmReservationResult.rejected("HARD_SPEND_LIMIT_EXCEEDED", null);
                }
            }
        }

        counter.setReservedTokens(Math.addExact(counter.getReservedTokens(), requestedTokens));
        counter.setReservedSpendMicroUsd(Math.addExact(counter.getReservedSpendMicroUsd(), requestedSpendMicroUsd));
        organizationUsageCounterRepository.save(counter);

        decision.setReservationId(fallbackDecisionId);
        decision.setDecisionStatus(LlmFallbackDecisionStatus.CAPACITY_RESERVED);
        fallbackDecisionRepository.save(decision);

        LlmProviderAttempt target = targetAttemptId != null ? providerAttemptRepository.findById(targetAttemptId).orElse(null) : null;
        return LlmReservationResult.success(requestedTokens, requestedSpendMicroUsd, target);
    }

    @Override
    @Transactional
    public LlmReservationResult releaseReservation(UUID attemptId) {
        LlmProviderAttempt attempt = providerAttemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Provider attempt not found: " + attemptId));

        Organization organization = attempt.getLogicalRequest().getOrganization();
        OrganizationUsageCounter counter = getOrLockUsageCounter(organization);

        if (entityManager != null && entityManager.contains(attempt)) {
            entityManager.refresh(attempt);
        }

        // Idempotency check: if already released or exempt
        if (attempt.getBillingStatus() == LlmBillingStatus.EXEMPT_NO_CHARGE) {
            return LlmReservationResult.success(0, 0L, attempt);
        }

        int reservedTokensToRelease = attempt.getPromptTokens() != null ? attempt.getPromptTokens() : 0;
        long reservedSpendToRelease = attempt.getEstimatedCostMicroUsd() != null ? attempt.getEstimatedCostMicroUsd() : 0L;

        counter.setReservedTokens(Math.max(0, counter.getReservedTokens() - reservedTokensToRelease));
        counter.setReservedSpendMicroUsd(Math.max(0L, counter.getReservedSpendMicroUsd() - reservedSpendToRelease));
        organizationUsageCounterRepository.save(counter);

        attempt.setBillingStatus(LlmBillingStatus.EXEMPT_NO_CHARGE);
        attempt.setEstimatedCostMicroUsd(0L);
        attempt.setPromptTokens(0);
        attempt = providerAttemptRepository.save(attempt);

        return LlmReservationResult.success(0, 0L, attempt);
    }

    @Override
    @Transactional
    public LlmReservationResult settleReservation(UUID attemptId, int actualTokens, long actualSpendMicroUsd) {
        LlmProviderAttempt attempt = providerAttemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Provider attempt not found: " + attemptId));

        Organization organization = attempt.getLogicalRequest().getOrganization();
        OrganizationUsageCounter counter = getOrLockUsageCounter(organization);

        if (entityManager != null && entityManager.contains(attempt)) {
            entityManager.refresh(attempt);
        }

        // Idempotency check post lock acquisition & entity refresh
        if (attempt.getBillingStatus() == LlmBillingStatus.BILLED || attempt.getBillingStatus() == LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY) {
            return LlmReservationResult.success(actualTokens, actualSpendMicroUsd, attempt);
        }

        int reservedTokensToRelease = attempt.getPromptTokens() != null ? attempt.getPromptTokens() : 0;
        long reservedSpendToRelease = attempt.getEstimatedCostMicroUsd() != null ? attempt.getEstimatedCostMicroUsd() : 0L;

        // Release reservation & commit actual usage with checked arithmetic
        counter.setReservedTokens(Math.max(0, counter.getReservedTokens() - reservedTokensToRelease));
        counter.setReservedSpendMicroUsd(Math.max(0L, counter.getReservedSpendMicroUsd() - reservedSpendToRelease));
        counter.setTokensConsumed(Math.addExact(counter.getTokensConsumed(), (long) actualTokens));
        counter.setTotalSpendCents(Math.addExact(counter.getTotalSpendCents(), actualSpendMicroUsd / 10_000L));
        organizationUsageCounterRepository.save(counter);

        boolean isOverage = actualSpendMicroUsd > reservedSpendToRelease;
        if (isOverage) {
            long liabilityMicroUsd = Math.subtractExact(actualSpendMicroUsd, reservedSpendToRelease);
            OrganizationOverageLiability liability = new OrganizationOverageLiability();
            liability.setOrganization(organization);
            liability.setAttempt(attempt);
            liability.setFinancialLiabilityMicroUsd(liabilityMicroUsd);
            liability.setReason("ACTUAL_USAGE_EXCEEDED_RESERVATION");
            overageLiabilityRepository.save(liability);

            attempt.setBillingStatus(LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY);
        } else {
            attempt.setBillingStatus(LlmBillingStatus.BILLED);
        }

        attempt.setTotalTokens(actualTokens);
        attempt.setCalculatedCostMicroUsd(actualSpendMicroUsd);
        attempt.setExecutionStatus(LlmExecutionStatus.SUCCEEDED);
        attempt = providerAttemptRepository.save(attempt);

        LlmLogicalRequest req = attempt.getLogicalRequest();
        req.setTotalTokensUsed(Math.addExact(req.getTotalTokensUsed(), actualTokens));
        req.setTotalBilledCostMicroUsd(Math.addExact(req.getTotalBilledCostMicroUsd(), actualSpendMicroUsd));
        req.setExecutionStatus(LlmExecutionStatus.SUCCEEDED);
        req.setBillingStatus(attempt.getBillingStatus());
        logicalRequestRepository.save(req);

        return LlmReservationResult.success(actualTokens, actualSpendMicroUsd, attempt);
    }

    @Override
    @Transactional
    public LlmReservationResult transferToUnknownExposure(UUID attemptId) {
        LlmProviderAttempt attempt = providerAttemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Provider attempt not found: " + attemptId));

        Organization organization = attempt.getLogicalRequest().getOrganization();
        OrganizationUsageCounter counter = getOrLockUsageCounter(organization);

        if (entityManager != null && entityManager.contains(attempt)) {
            entityManager.refresh(attempt);
        }

        if (attempt.getBillingStatus() == LlmBillingStatus.BILLING_UNKNOWN) {
            return LlmReservationResult.success(
                    attempt.getPromptTokens() != null ? attempt.getPromptTokens() : 0,
                    attempt.getEstimatedCostMicroUsd() != null ? attempt.getEstimatedCostMicroUsd() : 0L,
                    attempt);
        }

        int reservedTokensToTransfer = attempt.getPromptTokens() != null ? attempt.getPromptTokens() : 0;
        long reservedSpendToTransfer = attempt.getEstimatedCostMicroUsd() != null ? attempt.getEstimatedCostMicroUsd() : 0L;

        counter.setReservedTokens(Math.max(0, counter.getReservedTokens() - reservedTokensToTransfer));
        counter.setReservedSpendMicroUsd(Math.max(0L, counter.getReservedSpendMicroUsd() - reservedSpendToTransfer));
        counter.setUnresolvedExposureTokens(Math.addExact(counter.getUnresolvedExposureTokens(), reservedTokensToTransfer));
        counter.setUnresolvedExposureSpendMicroUsd(Math.addExact(counter.getUnresolvedExposureSpendMicroUsd(), reservedSpendToTransfer));
        organizationUsageCounterRepository.save(counter);

        attempt.setExecutionStatus(LlmExecutionStatus.TIMED_OUT);
        attempt.setBillingStatus(LlmBillingStatus.BILLING_UNKNOWN);
        attempt = providerAttemptRepository.save(attempt);

        return LlmReservationResult.success(reservedTokensToTransfer, reservedSpendToTransfer, attempt);
    }

    @Override
    @Transactional
    public LlmReservationResult resolveUnknownExposureZeroUsage(UUID attemptId) {
        LlmProviderAttempt attempt = providerAttemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Provider attempt not found: " + attemptId));

        Organization organization = attempt.getLogicalRequest().getOrganization();
        OrganizationUsageCounter counter = getOrLockUsageCounter(organization);

        if (entityManager != null && entityManager.contains(attempt)) {
            entityManager.refresh(attempt);
        }

        if (attempt.getBillingStatus() == LlmBillingStatus.EXEMPT_NO_CHARGE) {
            return LlmReservationResult.success(0, 0L, attempt);
        }

        int exposureTokens = attempt.getPromptTokens() != null ? attempt.getPromptTokens() : 0;
        long exposureSpend = attempt.getEstimatedCostMicroUsd() != null ? attempt.getEstimatedCostMicroUsd() : 0L;

        counter.setUnresolvedExposureTokens(Math.max(0, counter.getUnresolvedExposureTokens() - exposureTokens));
        counter.setUnresolvedExposureSpendMicroUsd(Math.max(0L, counter.getUnresolvedExposureSpendMicroUsd() - exposureSpend));
        organizationUsageCounterRepository.save(counter);

        attempt.setBillingStatus(LlmBillingStatus.EXEMPT_NO_CHARGE);
        attempt = providerAttemptRepository.save(attempt);

        return LlmReservationResult.success(0, 0L, attempt);
    }

    @Override
    @Transactional
    public LlmReservationResult resolveUnknownExposureActualUsage(UUID attemptId, int actualTokens, long actualSpendMicroUsd) {
        LlmProviderAttempt attempt = providerAttemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Provider attempt not found: " + attemptId));

        Organization organization = attempt.getLogicalRequest().getOrganization();
        OrganizationUsageCounter counter = getOrLockUsageCounter(organization);

        if (entityManager != null && entityManager.contains(attempt)) {
            entityManager.refresh(attempt);
        }

        if (attempt.getBillingStatus() == LlmBillingStatus.BILLED || attempt.getBillingStatus() == LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY) {
            return LlmReservationResult.success(actualTokens, actualSpendMicroUsd, attempt);
        }

        int exposureTokens = attempt.getPromptTokens() != null ? attempt.getPromptTokens() : 0;
        long exposureSpend = attempt.getEstimatedCostMicroUsd() != null ? attempt.getEstimatedCostMicroUsd() : 0L;

        counter.setUnresolvedExposureTokens(Math.max(0, counter.getUnresolvedExposureTokens() - exposureTokens));
        counter.setUnresolvedExposureSpendMicroUsd(Math.max(0L, counter.getUnresolvedExposureSpendMicroUsd() - exposureSpend));
        counter.setTokensConsumed(Math.addExact(counter.getTokensConsumed(), (long) actualTokens));
        counter.setTotalSpendCents(Math.addExact(counter.getTotalSpendCents(), actualSpendMicroUsd / 10_000L));
        organizationUsageCounterRepository.save(counter);

        boolean isOverage = actualSpendMicroUsd > exposureSpend;
        if (isOverage) {
            long liabilityMicroUsd = Math.subtractExact(actualSpendMicroUsd, exposureSpend);
            OrganizationOverageLiability liability = new OrganizationOverageLiability();
            liability.setOrganization(organization);
            liability.setAttempt(attempt);
            liability.setFinancialLiabilityMicroUsd(liabilityMicroUsd);
            liability.setReason("ACTUAL_USAGE_EXCEEDED_EXPOSURE");
            overageLiabilityRepository.save(liability);

            attempt.setBillingStatus(LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY);
        } else {
            attempt.setBillingStatus(LlmBillingStatus.BILLED);
        }

        attempt.setTotalTokens(actualTokens);
        attempt.setCalculatedCostMicroUsd(actualSpendMicroUsd);
        attempt.setExecutionStatus(LlmExecutionStatus.SUCCEEDED);
        attempt = providerAttemptRepository.save(attempt);

        LlmLogicalRequest req = attempt.getLogicalRequest();
        req.setTotalTokensUsed(Math.addExact(req.getTotalTokensUsed(), actualTokens));
        req.setTotalBilledCostMicroUsd(Math.addExact(req.getTotalBilledCostMicroUsd(), actualSpendMicroUsd));
        req.setExecutionStatus(LlmExecutionStatus.SUCCEEDED);
        req.setBillingStatus(attempt.getBillingStatus());
        logicalRequestRepository.save(req);

        return LlmReservationResult.success(actualTokens, actualSpendMicroUsd, attempt);
    }

    private OrganizationUsageCounter getOrLockUsageCounter(Organization organization) {
        Optional<OrganizationUsageCounter> counterOpt = organizationUsageCounterRepository.findByOrganizationIdWithLock(organization.getId());
        if (counterOpt.isPresent()) {
            return counterOpt.get();
        } else {
            try {
                OrganizationUsageCounter newCounter = new OrganizationUsageCounter(organization);
                organizationUsageCounterRepository.saveAndFlush(newCounter);
            } catch (Exception ignored) {
                // Another concurrent thread inserted the initial counter row first
            }
            return organizationUsageCounterRepository.findByOrganizationIdWithLock(organization.getId())
                    .orElseGet(() -> organizationUsageCounterRepository.findByOrganizationId(organization.getId()).orElseThrow());
        }
    }
}

package com.repopilot.llm.service;

import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.ProviderCapabilityResponse;
import com.repopilot.llm.dto.ProviderHealthResponse;
import com.repopilot.llm.entity.LlmBillingStatus;
import com.repopilot.llm.entity.LlmExecutionStatus;
import com.repopilot.llm.entity.LlmProviderAttempt;
import com.repopilot.llm.provider.registry.LlmProviderCapabilityRegistry;
import com.repopilot.llm.repository.LlmProviderAttemptRepository;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.service.OrganizationAuthorizationService;
import com.repopilot.user.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class LlmProviderInspectionServiceImpl implements LlmProviderInspectionService {

    private final OrganizationAuthorizationService authorizationService;
    private final LlmProviderAttemptRepository attemptRepository;

    @Autowired
    public LlmProviderInspectionServiceImpl(OrganizationAuthorizationService authorizationService,
                                             LlmProviderAttemptRepository attemptRepository) {
        this.authorizationService = authorizationService;
        this.attemptRepository = attemptRepository;
    }

    @Override
    public List<ProviderCapabilityResponse> getRegisteredProviders(UUID orgId, User principalUser) {
        validateOrgId(orgId);
        validateUser(principalUser);

        authorizationService.requireMinimumRole(orgId, principalUser, OrganizationRole.ORG_MEMBER);

        List<String> providerIds = LlmProviderCapabilityRegistry.getRegisteredProviderIds();
        List<ProviderCapabilityResponse> responses = new ArrayList<>();
        for (String pid : providerIds) {
            responses.add(ProviderCapabilityResponse.fromRegistry(pid));
        }
        return Collections.unmodifiableList(responses);
    }

    @Override
    public ProviderCapabilityResponse getProviderCapability(UUID orgId, String providerId, User principalUser) {
        validateOrgId(orgId);
        validateProviderId(providerId);
        validateUser(principalUser);

        authorizationService.requireMinimumRole(orgId, principalUser, OrganizationRole.ORG_MEMBER);

        String normalizedPid = providerId.trim().toLowerCase();
        if (!LlmProviderCapabilityRegistry.isSupportedProvider(normalizedPid)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Provider not found in registry: " + providerId);
        }

        return ProviderCapabilityResponse.fromRegistry(normalizedPid);
    }

    @Override
    public ProviderHealthResponse getProviderHealth(UUID orgId, String providerId, User principalUser) {
        validateOrgId(orgId);
        validateProviderId(providerId);
        validateUser(principalUser);

        authorizationService.requireMinimumRole(orgId, principalUser, OrganizationRole.ORG_MEMBER);

        String normalizedPid = providerId.trim().toLowerCase();
        if (!LlmProviderCapabilityRegistry.isSupportedProvider(normalizedPid)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Provider not found in registry: " + providerId);
        }

        List<LlmProviderAttempt> attempts = attemptRepository.findRecentAttemptsByOrganizationAndProvider(
                orgId, normalizedPid, PageRequest.of(0, 100));

        ProviderHealthResponse response = new ProviderHealthResponse();
        response.setProviderId(normalizedPid);
        response.setOrganizationId(orgId);
        response.setObservationWindowLimit(100);
        response.setRecentAttemptCount(attempts.size());

        if (attempts.isEmpty()) {
            response.setHealthStatus("NO_RECENT_DATA");
            response.setLastAttemptAt(null);
            response.setLastSuccessfulAttemptAt(null);
            response.setRecentSuccessCount(0);
            response.setRecentFailureCount(0);
            response.setRecentUnknownOutcomeCount(0);
            response.setRecentRateLimitedCount(0);
            response.setRecentReconciliationRequiredCount(0);
        } else {
            response.setHealthStatus("OBSERVED");
            response.setLastAttemptAt(attempts.get(0).getStartTime());

            long successCount = 0;
            long failureCount = 0;
            long unknownCount = 0;
            long rateLimitCount = 0;
            long reconRequiredCount = 0;
            Instant lastSuccessAt = null;

            for (LlmProviderAttempt attempt : attempts) {
                LlmExecutionStatus status = attempt.getExecutionStatus();
                LlmBillingStatus billing = attempt.getBillingStatus();
                String fallbackReason = attempt.getFallbackReason();

                if (status == LlmExecutionStatus.SUCCEEDED || status == LlmExecutionStatus.SUCCESS) {
                    successCount++;
                    if (lastSuccessAt == null || (attempt.getStartTime() != null && attempt.getStartTime().isAfter(lastSuccessAt))) {
                        lastSuccessAt = attempt.getStartTime();
                    }
                } else if (status == LlmExecutionStatus.TIMED_OUT || status == LlmExecutionStatus.TIMEOUT || billing == LlmBillingStatus.BILLING_UNKNOWN) {
                    unknownCount++;
                } else if (status == LlmExecutionStatus.RATE_LIMITED || (fallbackReason != null && fallbackReason.toUpperCase().contains("RATE_LIMIT"))) {
                    rateLimitCount++;
                    failureCount++;
                } else if (status == LlmExecutionStatus.FAILED || status == LlmExecutionStatus.REJECTED_SECURITY || status == LlmExecutionStatus.REJECTED_POLICY || status == LlmExecutionStatus.TRANSPORT_ERROR || status == LlmExecutionStatus.MALFORMED_OUTPUT || status == LlmExecutionStatus.VALIDATION_FAILED || status == LlmExecutionStatus.FAILED_EXHAUSTED) {
                    failureCount++;
                }

                if (billing == LlmBillingStatus.RECONCILIATION_FAILED || billing == LlmBillingStatus.BILLING_PENDING_PROVIDER || billing == LlmBillingStatus.BILLING_UNKNOWN) {
                    reconRequiredCount++;
                }
            }

            response.setLastSuccessfulAttemptAt(lastSuccessAt);
            response.setRecentSuccessCount(successCount);
            response.setRecentFailureCount(failureCount);
            response.setRecentUnknownOutcomeCount(unknownCount);
            response.setRecentRateLimitedCount(rateLimitCount);
            response.setRecentReconciliationRequiredCount(reconRequiredCount);
        }

        return response;
    }

    private void validateOrgId(UUID orgId) {
        if (orgId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Organization ID must not be null");
        }
    }

    private void validateUser(User user) {
        if (user == null || user.getId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
    }

    private void validateProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Provider ID must not be null or blank");
        }
        if (providerId.length() > 64 || !providerId.matches("^[a-zA-Z0-9_-]+$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid provider ID format");
        }
    }
}

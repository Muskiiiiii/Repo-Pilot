package com.repopilot.llm.service;

import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.*;
import com.repopilot.llm.entity.*;
import com.repopilot.llm.provider.registry.LlmProviderCapabilityRegistry;
import com.repopilot.llm.repository.*;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.service.OrganizationAuthorizationService;
import com.repopilot.user.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LlmPolicyAdministrationServiceImpl implements LlmPolicyAdministrationService {

    private final LlmExceptionalFallbackPolicyRepository exceptionalPolicyRepository;
    private final LlmActiveExceptionalFallbackPolicyRepository activePolicyRepository;
    private final OrganizationAuthorizationService organizationAuthorizationService;
    private final AuditLogOutboxRepository auditLogOutboxRepository;
    private final com.repopilot.organization.repository.OrganizationRepository organizationRepository;

    @Autowired
    public LlmPolicyAdministrationServiceImpl(LlmExceptionalFallbackPolicyRepository exceptionalPolicyRepository,
                                               LlmActiveExceptionalFallbackPolicyRepository activePolicyRepository,
                                               OrganizationAuthorizationService organizationAuthorizationService,
                                               AuditLogOutboxRepository auditLogOutboxRepository,
                                               com.repopilot.organization.repository.OrganizationRepository organizationRepository) {
        this.exceptionalPolicyRepository = exceptionalPolicyRepository;
        this.activePolicyRepository = activePolicyRepository;
        this.organizationAuthorizationService = organizationAuthorizationService;
        this.auditLogOutboxRepository = auditLogOutboxRepository;
        this.organizationRepository = organizationRepository;
    }

    @Override
    @Transactional
    public ExceptionalPolicyResponse createExceptionalPolicyVersion(UUID orgId, User caller, CreateExceptionalPolicyRequest request) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_ADMIN);
        validateCreateRequest(request);

        Organization organization = organizationAuthorizationService.getAuthorizedOrganization(orgId, caller);

        String srcType = request.getSourceAuthorityType().trim();
        String origProv = request.getOriginalProviderId().trim().toLowerCase();
        String targetProv = request.getTargetProviderId().trim().toLowerCase();
        String reason = request.getReason().trim();

        Optional<LlmActiveExceptionalFallbackPolicy> existingActiveOpt = activePolicyRepository
                .findByOrganizationIdAndSourceAuthorityTypeAndOriginalProviderIdAndTargetProviderId(orgId, srcType, origProv, targetProv);

        if (existingActiveOpt.isEmpty()) {
            long currentActiveCount = activePolicyRepository.countByOrganizationId(orgId);
            if (currentActiveCount >= 50) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EXCEPTIONAL_POLICY_LIMIT_EXCEEDED",
                        "Maximum active exceptional policy limit (50) reached for organization");
            }
        }

        int maxVersion = exceptionalPolicyRepository.findMaxPolicyVersion(orgId, srcType, origProv, targetProv);
        int nextVersion = maxVersion + 1;

        LlmExceptionalFallbackPolicy policy = new LlmExceptionalFallbackPolicy(
                organization, srcType, origProv, targetProv, nextVersion, caller, reason
        );
        policy = exceptionalPolicyRepository.save(policy);

        LlmActiveExceptionalFallbackPolicy activePointer;
        if (existingActiveOpt.isPresent()) {
            activePointer = existingActiveOpt.get();
            activePointer.setActivePolicy(policy);
            activePointer.setUpdatedAt(Instant.now());
            activePointer = activePolicyRepository.save(activePointer);
        } else {
            activePointer = new LlmActiveExceptionalFallbackPolicy(organization, srcType, origProv, targetProv, policy);
            activePointer = activePolicyRepository.save(activePointer);
        }

        recordAuditOutboxEvent(policy, "EXCEPTIONAL_POLICY_CREATED", orgId);

        return mapToExceptionalPolicyResponse(policy, true);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedPolicyResponse<ExceptionalPolicyResponse> getExceptionalPolicyVersions(UUID orgId, User caller, int page, int size) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_MEMBER);

        int cappedSize = Math.min(Math.max(size, 1), 100);
        int validPage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(validPage, cappedSize);

        Page<LlmExceptionalFallbackPolicy> pageResult = exceptionalPolicyRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId, pageable);

        List<LlmActiveExceptionalFallbackPolicy> activePointers = activePolicyRepository.findByOrganizationId(orgId);
        Set<UUID> activePolicyIds = activePointers.stream()
                .map(a -> a.getActivePolicy().getId())
                .collect(Collectors.toSet());

        List<ExceptionalPolicyResponse> dtoList = pageResult.getContent().stream()
                .map(p -> mapToExceptionalPolicyResponse(p, activePolicyIds.contains(p.getId())))
                .collect(Collectors.toList());

        return new PagedPolicyResponse<>(dtoList, pageResult.getNumber(), pageResult.getSize(), pageResult.getTotalElements(), pageResult.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public ExceptionalPolicyResponse getSpecificPolicyVersion(UUID orgId, UUID policyId, User caller) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_MEMBER);

        LlmExceptionalFallbackPolicy policy = exceptionalPolicyRepository.findById(policyId)
                .orElse(null);

        if (policy == null || !policy.getOrganization().getId().equals(orgId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Exceptional fallback policy version not found");
        }

        Optional<LlmActiveExceptionalFallbackPolicy> activeOpt = activePolicyRepository
                .findByOrganizationIdAndSourceAuthorityTypeAndOriginalProviderIdAndTargetProviderId(
                        orgId, policy.getSourceAuthorityType(), policy.getOriginalProviderId(), policy.getTargetProviderId());

        boolean isActive = activeOpt.isPresent() && activeOpt.get().getActivePolicy().getId().equals(policy.getId());

        return mapToExceptionalPolicyResponse(policy, isActive);
    }

    @Override
    @Transactional(readOnly = true)
    public ActiveExceptionalPolicyResponse getActivePointer(UUID orgId, String sourceAuthorityType, String originalProviderId, String targetProviderId, User caller) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_MEMBER);

        String srcType = validateStringParameter(sourceAuthorityType, "sourceAuthorityType", 64);
        String origProv = validateStringParameter(originalProviderId, "originalProviderId", 32).toLowerCase();
        String targetProv = validateStringParameter(targetProviderId, "targetProviderId", 32).toLowerCase();

        LlmActiveExceptionalFallbackPolicy activePointer = activePolicyRepository
                .findByOrganizationIdAndSourceAuthorityTypeAndOriginalProviderIdAndTargetProviderId(orgId, srcType, origProv, targetProv)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No active exceptional policy pointer found for key"));

        return mapToActivePointerResponse(activePointer);
    }

    @Override
    @Transactional
    public ActiveExceptionalPolicyResponse activatePolicyVersion(UUID orgId, UUID policyId, User admin) {
        organizationAuthorizationService.requireMinimumRole(orgId, admin, OrganizationRole.ORG_ADMIN);

        LlmExceptionalFallbackPolicy policy = exceptionalPolicyRepository.findById(policyId)
                .orElse(null);

        if (policy == null || !policy.getOrganization().getId().equals(orgId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Exceptional fallback policy version not found");
        }

        Optional<LlmActiveExceptionalFallbackPolicy> activeOpt = activePolicyRepository
                .findByOrganizationIdAndSourceAuthorityTypeAndOriginalProviderIdAndTargetProviderId(
                        orgId, policy.getSourceAuthorityType(), policy.getOriginalProviderId(), policy.getTargetProviderId());

        LlmActiveExceptionalFallbackPolicy activePointer;
        if (activeOpt.isPresent()) {
            activePointer = activeOpt.get();
            activePointer.setActivePolicy(policy);
            activePointer.setUpdatedAt(Instant.now());
            activePointer = activePolicyRepository.save(activePointer);
        } else {
            Organization org = organizationAuthorizationService.getAuthorizedOrganization(orgId, admin);
            activePointer = new LlmActiveExceptionalFallbackPolicy(
                    org, policy.getSourceAuthorityType(), policy.getOriginalProviderId(), policy.getTargetProviderId(), policy
            );
            activePointer = activePolicyRepository.save(activePointer);
        }

        recordAuditOutboxEvent(policy, "EXCEPTIONAL_POLICY_ACTIVATED", orgId);

        return mapToActivePointerResponse(activePointer);
    }

    @Override
    @Transactional
    public void deactivateActivePolicy(UUID orgId, String sourceAuthorityType, String originalProviderId, String targetProviderId, User admin) {
        organizationAuthorizationService.requireMinimumRole(orgId, admin, OrganizationRole.ORG_ADMIN);

        String srcType = validateStringParameter(sourceAuthorityType, "sourceAuthorityType", 64);
        String origProv = validateStringParameter(originalProviderId, "originalProviderId", 32).toLowerCase();
        String targetProv = validateStringParameter(targetProviderId, "targetProviderId", 32).toLowerCase();

        Optional<LlmActiveExceptionalFallbackPolicy> activeOpt = activePolicyRepository
                .findByOrganizationIdAndSourceAuthorityTypeAndOriginalProviderIdAndTargetProviderId(orgId, srcType, origProv, targetProv);

        if (activeOpt.isPresent()) {
            LlmActiveExceptionalFallbackPolicy activePointer = activeOpt.get();
            activePolicyRepository.delete(activePointer);
            recordDeactivateAuditOutboxEvent(orgId, srcType, origProv, targetProv);
        }
    }

    private void validateCreateRequest(CreateExceptionalPolicyRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request body cannot be null");
        }
        validateStringParameter(request.getSourceAuthorityType(), "sourceAuthorityType", 64);
        String origProv = validateStringParameter(request.getOriginalProviderId(), "originalProviderId", 32).toLowerCase();
        String targetProv = validateStringParameter(request.getTargetProviderId(), "targetProviderId", 32).toLowerCase();

        if (!LlmProviderCapabilityRegistry.isSupportedProvider(origProv)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_TARGET_CAPABILITY", "Original provider is not supported: " + origProv);
        }
        if (!LlmProviderCapabilityRegistry.isSupportedProvider(targetProv)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_TARGET_CAPABILITY", "Target provider is not supported: " + targetProv);
        }

        String reason = request.getReason();
        if (reason == null || reason.trim().isEmpty() || reason.length() > 2048) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT_LENGTH", "Policy reason must be between 1 and 2048 characters");
        }
    }

    private String validateStringParameter(String value, String paramName, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", paramName + " cannot be empty");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT_LENGTH", paramName + " exceeds maximum length of " + maxLength);
        }
        return trimmed;
    }

    private ExceptionalPolicyResponse mapToExceptionalPolicyResponse(LlmExceptionalFallbackPolicy policy, boolean isActive) {
        return new ExceptionalPolicyResponse(
                policy.getId(),
                policy.getOrganization().getId(),
                policy.getSourceAuthorityType(),
                policy.getOriginalProviderId(),
                policy.getTargetProviderId(),
                policy.getPolicyVersion(),
                policy.getCreatedByUser() != null ? policy.getCreatedByUser().getId() : null,
                policy.getReason(),
                policy.getCreatedAt(),
                isActive
        );
    }

    private ActiveExceptionalPolicyResponse mapToActivePointerResponse(LlmActiveExceptionalFallbackPolicy pointer) {
        return new ActiveExceptionalPolicyResponse(
                pointer.getId(),
                pointer.getOrganization().getId(),
                pointer.getSourceAuthorityType(),
                pointer.getOriginalProviderId(),
                pointer.getTargetProviderId(),
                pointer.getActivePolicy().getId(),
                pointer.getActivePolicy().getPolicyVersion(),
                pointer.getVersion(),
                pointer.getCreatedAt(),
                pointer.getUpdatedAt()
        );
    }

    private void recordAuditOutboxEvent(LlmExceptionalFallbackPolicy policy, String eventType, UUID orgId) {
        AuditLogOutbox outbox = new AuditLogOutbox();
        UUID occurrenceId = UUID.randomUUID();
        String canonicalId = "POL:" + orgId + ":" + policy.getId() + ":" + policy.getPolicyVersion() + ":" + eventType + ":" + occurrenceId;
        if (canonicalId.length() > 128) {
            canonicalId = canonicalId.substring(0, 128);
        }
        outbox.setCanonicalAuditEventId(canonicalId);
        outbox.setOccurrenceId(occurrenceId);
        if (orgId != null) {
            organizationRepository.findById(orgId).ifPresent(outbox::setOrganization);
        }
        outbox.setAggregateType("LlmExceptionalFallbackPolicy");
        outbox.setAggregateId(policy.getId().toString());
        outbox.setEventType(eventType);
        outbox.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        outbox.setCreatedAt(Instant.now());

        String payload = String.format("{\"organizationId\":\"%s\",\"policyId\":\"%s\",\"policyVersion\":%d,\"sourceAuthorityType\":\"%s\",\"originalProviderId\":\"%s\",\"targetProviderId\":\"%s\",\"eventType\":\"%s\"}",
                orgId, policy.getId(), policy.getPolicyVersion(), policy.getSourceAuthorityType(), policy.getOriginalProviderId(), policy.getTargetProviderId(), eventType);
        outbox.setPayloadJson(payload);

        auditLogOutboxRepository.save(outbox);
    }

    private void recordDeactivateAuditOutboxEvent(UUID orgId, String srcType, String origProv, String targetProv) {
        AuditLogOutbox outbox = new AuditLogOutbox();
        UUID occurrenceId = UUID.randomUUID();
        String canonicalId = "POL:" + orgId + ":" + srcType + ":" + origProv + ":" + targetProv + ":DEACTIVATED:" + occurrenceId;
        if (canonicalId.length() > 128) {
            canonicalId = canonicalId.substring(0, 128);
        }
        outbox.setCanonicalAuditEventId(canonicalId);
        outbox.setOccurrenceId(occurrenceId);
        if (orgId != null) {
            organizationRepository.findById(orgId).ifPresent(outbox::setOrganization);
        }
        outbox.setAggregateType("LlmActiveExceptionalFallbackPolicy");
        outbox.setAggregateId(orgId.toString());
        outbox.setEventType("EXCEPTIONAL_POLICY_DEACTIVATED");
        outbox.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        outbox.setCreatedAt(Instant.now());

        String payload = String.format("{\"organizationId\":\"%s\",\"sourceAuthorityType\":\"%s\",\"originalProviderId\":\"%s\",\"targetProviderId\":\"%s\",\"eventType\":\"EXCEPTIONAL_POLICY_DEACTIVATED\"}",
                orgId, srcType, origProv, targetProv);
        outbox.setPayloadJson(payload);

        auditLogOutboxRepository.save(outbox);
    }
}

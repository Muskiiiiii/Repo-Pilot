package com.repopilot.organization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.organization.dto.OrganizationPolicyDto;
import com.repopilot.organization.dto.UpdateOrganizationPolicyRequest;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationPolicy;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationPolicyRepository;
import com.repopilot.user.entity.User;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OrganizationPolicyServiceImpl implements OrganizationPolicyService {

    private final OrganizationPolicyRepository organizationPolicyRepository;
    private final OrganizationAuthorizationService organizationAuthorizationService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public OrganizationPolicyServiceImpl(OrganizationPolicyRepository organizationPolicyRepository,
                                         OrganizationAuthorizationService organizationAuthorizationService,
                                         AuditLogService auditLogService,
                                         ObjectMapper objectMapper) {
        this.organizationPolicyRepository = organizationPolicyRepository;
        this.organizationAuthorizationService = organizationAuthorizationService;
        this.auditLogService = auditLogService;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationPolicyDto getPolicy(UUID orgId, User user) {
        Organization organization = organizationAuthorizationService.getAuthorizedOrganization(orgId, user);
        organizationAuthorizationService.requireMinimumRole(orgId, user, OrganizationRole.ORG_MEMBER);

        OrganizationPolicy policy = organizationPolicyRepository.findByOrganizationId(organization.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "Organization policy not found"));

        return mapToDto(policy);
    }

    @Override
    @Transactional
    public OrganizationPolicyDto updatePolicy(UUID orgId, User user, UpdateOrganizationPolicyRequest request) {
        Organization organization = organizationAuthorizationService.getAuthorizedOrganization(orgId, user);
        organizationAuthorizationService.requireMinimumRole(orgId, user, OrganizationRole.ORG_ADMIN);

        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Policy request body cannot be null");
        }

        OrganizationPolicy policy = organizationPolicyRepository.findByOrganizationId(organization.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "Organization policy not found"));

        // Check optimistic lock version
        if (request.getVersion() != null && !request.getVersion().equals(policy.getVersion())) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Policy has been modified by another request. Please reload and try again.");
        }

        try {
            policy.setMonthlyTokenBudget(request.getMonthlyTokenBudget());
            policy.setMonthlySpendLimitUsd(request.getMonthlySpendLimitUsd());
            policy.setAllowedLlmModelsJson(toJson(request.getAllowedLlmModels()));
            policy.setEnforceSecurityReview(request.isEnforceSecurityReview());
            policy.setMaxConcurrentExecutions(request.getMaxConcurrentExecutions());
            policy.setAuditRetentionDays(request.getAuditRetentionDays());

            OrganizationPolicy saved = organizationPolicyRepository.save(policy);
            organizationPolicyRepository.flush();

            auditLogService.logAction(user, null, null, "ORGANIZATION_POLICY_UPDATED", "ORGANIZATION_POLICY", saved.getId(),
                    "{\"organizationId\":\"" + orgId + "\",\"version\":" + saved.getVersion() + "}");

            return mapToDto(saved);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Concurrent policy update detected. Please retry.", ex);
        }
    }

    private OrganizationPolicyDto mapToDto(OrganizationPolicy policy) {
        List<String> allowedModels = parseJsonList(policy.getAllowedLlmModelsJson());
        return new OrganizationPolicyDto(
                policy.getId(),
                policy.getOrganization().getId(),
                policy.getMonthlyTokenBudget(),
                policy.getMonthlySpendLimitUsd(),
                allowedModels,
                policy.isEnforceSecurityReview(),
                policy.getMaxConcurrentExecutions(),
                policy.getAuditRetentionDays(),
                policy.getVersion(),
                policy.getUpdatedAt()
        );
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[\"claude-3-5-sonnet\", \"gpt-4o\"]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(String json) {
        if (json == null || json.trim().isEmpty()) {
            return List.of("claude-3-5-sonnet", "gpt-4o");
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of("claude-3-5-sonnet", "gpt-4o");
        }
    }
}

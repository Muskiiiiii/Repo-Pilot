package com.repopilot.organization.service;

import com.repopilot.common.exception.ApiException;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.user.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrganizationAuthorizationServiceImpl implements OrganizationAuthorizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository organizationMemberRepository;

    public OrganizationAuthorizationServiceImpl(OrganizationRepository organizationRepository,
                                                 OrganizationMemberRepository organizationMemberRepository) {
        this.organizationRepository = organizationRepository;
        this.organizationMemberRepository = organizationMemberRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Organization getAuthorizedOrganization(UUID orgId, User user) {
        if (orgId == null || user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }

        Organization organization = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Organization not found"));

        if (!hasAccess(orgId, user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not authorized to access this organization");
        }

        return organization;
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationRole getUserRoleInOrganization(UUID orgId, User user) {
        if (orgId == null || user == null) return null;
        Optional<OrganizationMember> memberOpt = organizationMemberRepository.findByOrganizationIdAndUserId(orgId, user.getId());
        if (memberOpt.isPresent()) {
            return memberOpt.get().getRole();
        }
        if (com.repopilot.organization.config.DefaultOrganizationHolder.DEFAULT_ORG_ID.equals(orgId) && !organizationMemberRepository.existsByUserId(user.getId())) {
            return OrganizationRole.ORG_MEMBER;
        }
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAccess(UUID orgId, User user) {
        if (orgId == null || user == null) return false;
        if (organizationMemberRepository.existsByOrganizationIdAndUserId(orgId, user.getId())) {
            return true;
        }
        if (com.repopilot.organization.config.DefaultOrganizationHolder.DEFAULT_ORG_ID.equals(orgId)) {
            return !organizationMemberRepository.existsByUserId(user.getId());
        }
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void requireMinimumRole(UUID orgId, User user, OrganizationRole minimumRole) {
        OrganizationRole userRole = getUserRoleInOrganization(orgId, user);
        if (userRole == null || getRoleLevel(userRole) < getRoleLevel(minimumRole)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permissions for this organization operation");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Organization> getUserOrganizations(User user) {
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        List<OrganizationMember> memberships = organizationMemberRepository.findAllByUserIdWithOrganization(user.getId());
        List<Organization> orgs = memberships.stream()
                .map(OrganizationMember::getOrganization)
                .collect(Collectors.toList());
        if (orgs.isEmpty()) {
            organizationRepository.findById(com.repopilot.organization.config.DefaultOrganizationHolder.DEFAULT_ORG_ID)
                    .ifPresent(orgs::add);
        }
        return orgs;
    }

    private int getRoleLevel(OrganizationRole role) {
        if (role == null) return 0;
        return switch (role) {
            case ORG_ADMIN -> 2;
            case ORG_MEMBER -> 1;
        };
    }
}

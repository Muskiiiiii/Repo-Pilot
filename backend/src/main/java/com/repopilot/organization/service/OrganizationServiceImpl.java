package com.repopilot.organization.service;

import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.organization.dto.AddOrgMemberRequest;
import com.repopilot.organization.dto.CreateOrganizationRequest;
import com.repopilot.organization.dto.OrganizationDto;
import com.repopilot.organization.dto.OrganizationMemberDto;
import com.repopilot.organization.dto.UpdateOrganizationMemberRoleRequest;
import com.repopilot.organization.entity.*;
import com.repopilot.organization.repository.*;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrganizationServiceImpl implements OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final OrganizationPolicyRepository organizationPolicyRepository;
    private final OrganizationUsageCounterRepository organizationUsageCounterRepository;
    private final OrganizationAuthorizationService organizationAuthorizationService;
    private final AuditLogService auditLogService;
    private final UserRepository userRepository;

    public OrganizationServiceImpl(OrganizationRepository organizationRepository,
                                   OrganizationMemberRepository organizationMemberRepository,
                                   OrganizationPolicyRepository organizationPolicyRepository,
                                   OrganizationUsageCounterRepository organizationUsageCounterRepository,
                                   OrganizationAuthorizationService organizationAuthorizationService,
                                   AuditLogService auditLogService,
                                   UserRepository userRepository) {
        this.organizationRepository = organizationRepository;
        this.organizationMemberRepository = organizationMemberRepository;
        this.organizationPolicyRepository = organizationPolicyRepository;
        this.organizationUsageCounterRepository = organizationUsageCounterRepository;
        this.organizationAuthorizationService = organizationAuthorizationService;
        this.auditLogService = auditLogService;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public OrganizationDto createOrganization(User creator, CreateOrganizationRequest request) {
        if (creator == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }

        if (request == null || request.getName() == null || request.getName().trim().isEmpty()
                || request.getSlug() == null || request.getSlug().trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Organization name and slug are required");
        }

        String normalizedSlug = request.getSlug().trim().toLowerCase();
        if (organizationRepository.existsBySlug(normalizedSlug)) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_SLUG", "Organization slug '" + normalizedSlug + "' is already in use");
        }

        try {
            // 1. Create Organization entity
            Organization organization = new Organization(request.getName().trim(), normalizedSlug);
            Organization savedOrganization = organizationRepository.save(organization);

            // 2. Create Creator Admin Membership
            OrganizationMember member = new OrganizationMember(savedOrganization, creator, OrganizationRole.ORG_ADMIN);
            organizationMemberRepository.save(member);

            // 3. Create Default Organization Policy
            OrganizationPolicy policy = new OrganizationPolicy(savedOrganization);
            organizationPolicyRepository.save(policy);

            // 4. Create Usage Counter
            OrganizationUsageCounter usageCounter = new OrganizationUsageCounter(savedOrganization);
            organizationUsageCounterRepository.save(usageCounter);

            // 5. Audit Log
            auditLogService.logAction(creator, null, null, "ORGANIZATION_CREATED", "ORGANIZATION", savedOrganization.getId(), "{\"name\":\"" + savedOrganization.getName() + "\",\"slug\":\"" + savedOrganization.getSlug() + "\"}");

            return mapToDto(savedOrganization, OrganizationRole.ORG_ADMIN);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_SLUG", "Organization slug is already in use", ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationDto> getUserOrganizations(User user) {
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        List<OrganizationMember> memberships = organizationMemberRepository.findAllByUserIdWithOrganization(user.getId());
        return memberships.stream()
                .map(m -> mapToDto(m.getOrganization(), m.getRole()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationDto getOrganizationById(UUID orgId, User user) {
        Organization organization = organizationAuthorizationService.getAuthorizedOrganization(orgId, user);
        OrganizationRole role = organizationAuthorizationService.getUserRoleInOrganization(orgId, user);
        return mapToDto(organization, role);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationMemberDto> getOrganizationMembers(User currentUser, UUID organizationId) {
        if (currentUser == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        organizationAuthorizationService.requireMinimumRole(organizationId, currentUser, OrganizationRole.ORG_MEMBER);
        List<OrganizationMember> members = organizationMemberRepository.findByOrganizationId(organizationId);
        return members.stream()
                .map(OrganizationMemberDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrganizationMemberDto addOrganizationMember(User currentUser, UUID organizationId, AddOrgMemberRequest request) {
        if (currentUser == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        if (request == null || request.getEmail() == null || request.getEmail().trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "User email is required");
        }

        organizationAuthorizationService.requireMinimumRole(organizationId, currentUser, OrganizationRole.ORG_ADMIN);
        Organization org = organizationAuthorizationService.getAuthorizedOrganization(organizationId, currentUser);

        String email = request.getEmail().trim().toLowerCase();
        User targetUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found with email: " + request.getEmail()));

        if (organizationMemberRepository.existsByOrganizationIdAndUserId(organizationId, targetUser.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MEMBER_ALREADY_EXISTS", "User is already a member of this organization");
        }

        OrganizationRole assignedRole = request.getRole() != null ? request.getRole() : OrganizationRole.ORG_MEMBER;
        OrganizationMember newMember = new OrganizationMember(org, targetUser, assignedRole);

        OrganizationMember savedMember;
        try {
            savedMember = organizationMemberRepository.saveAndFlush(newMember);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MEMBER_ALREADY_EXISTS", "User is already a member of this organization", ex);
        }

        auditLogService.logAction(currentUser, org, null, null, "ORG_MEMBER_ADDED", "ORGANIZATION_MEMBER", savedMember.getId(), "Added member " + targetUser.getEmail() + " as " + assignedRole);

        return OrganizationMemberDto.fromEntity(savedMember);
    }

    @Override
    @Transactional
    public OrganizationMemberDto updateOrganizationMemberRole(User currentUser, UUID organizationId, UUID memberId, UpdateOrganizationMemberRoleRequest request) {
        if (currentUser == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        if (request == null || request.getRole() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Organization role is required");
        }

        organizationAuthorizationService.requireMinimumRole(organizationId, currentUser, OrganizationRole.ORG_ADMIN);

        // Acquire PESSIMISTIC_WRITE lock on parent Organization row
        Organization org = organizationRepository.findByIdForUpdate(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Organization not found"));

        OrganizationMember targetMember = organizationMemberRepository.findById(memberId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Organization member not found"));

        if (!targetMember.getOrganization().getId().equals(organizationId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MEMBER_NOT_IN_ORGANIZATION", "Member does not belong to the specified organization");
        }

        if (request.getVersion() != null && !request.getVersion().equals(targetMember.getVersion())) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Organization member state has been modified by another transaction");
        }

        // Sole-admin check under pessimistic parent row lock
        if (targetMember.getRole() == OrganizationRole.ORG_ADMIN && request.getRole() != OrganizationRole.ORG_ADMIN) {
            long activeAdminCount = organizationMemberRepository.countByOrganizationIdAndRole(organizationId, OrganizationRole.ORG_ADMIN);
            if (activeAdminCount <= 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "LAST_ADMIN_REMOVAL_FORBIDDEN", "Cannot demote the sole administrator of an organization");
            }
        }

        targetMember.setRole(request.getRole());
        OrganizationMember updatedMember = organizationMemberRepository.saveAndFlush(targetMember);

        auditLogService.logAction(currentUser, org, null, null, "ORG_MEMBER_ROLE_UPDATED", "ORGANIZATION_MEMBER", updatedMember.getId(), "Updated member " + targetMember.getUser().getEmail() + " role to " + updatedMember.getRole());

        return OrganizationMemberDto.fromEntity(updatedMember);
    }

    @Override
    @Transactional
    public void removeOrganizationMember(User currentUser, UUID organizationId, UUID memberId) {
        if (currentUser == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }

        organizationAuthorizationService.requireMinimumRole(organizationId, currentUser, OrganizationRole.ORG_ADMIN);

        // Acquire PESSIMISTIC_WRITE lock on parent Organization row
        Organization org = organizationRepository.findByIdForUpdate(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Organization not found"));

        OrganizationMember targetMember = organizationMemberRepository.findById(memberId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Organization member not found"));

        if (!targetMember.getOrganization().getId().equals(organizationId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MEMBER_NOT_IN_ORGANIZATION", "Member does not belong to the specified organization");
        }

        // Sole-admin check under pessimistic parent row lock
        if (targetMember.getRole() == OrganizationRole.ORG_ADMIN) {
            long activeAdminCount = organizationMemberRepository.countByOrganizationIdAndRole(organizationId, OrganizationRole.ORG_ADMIN);
            if (activeAdminCount <= 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "LAST_ADMIN_REMOVAL_FORBIDDEN", "Cannot remove the sole administrator of an organization");
            }
        }

        auditLogService.logAction(currentUser, org, null, null, "ORG_MEMBER_REMOVED", "ORGANIZATION_MEMBER", targetMember.getId(), "Removed member " + targetMember.getUser().getEmail());

        organizationMemberRepository.delete(targetMember);
        organizationMemberRepository.flush();
    }

    private OrganizationDto mapToDto(Organization org, OrganizationRole role) {
        return new OrganizationDto(
                org.getId(),
                org.getName(),
                org.getSlug(),
                role,
                org.getCreatedAt(),
                org.getUpdatedAt()
        );
    }
}

package com.repopilot.organization.service;

import com.repopilot.organization.dto.AddOrgMemberRequest;
import com.repopilot.organization.dto.CreateOrganizationRequest;
import com.repopilot.organization.dto.OrganizationDto;
import com.repopilot.organization.dto.OrganizationMemberDto;
import com.repopilot.organization.dto.UpdateOrganizationMemberRoleRequest;
import com.repopilot.user.entity.User;

import java.util.List;
import java.util.UUID;

public interface OrganizationService {

    OrganizationDto createOrganization(User creator, CreateOrganizationRequest request);

    List<OrganizationDto> getUserOrganizations(User user);

    OrganizationDto getOrganizationById(UUID orgId, User user);

    List<OrganizationMemberDto> getOrganizationMembers(User currentUser, UUID organizationId);

    OrganizationMemberDto addOrganizationMember(User currentUser, UUID organizationId, AddOrgMemberRequest request);

    OrganizationMemberDto updateOrganizationMemberRole(User currentUser, UUID organizationId, UUID memberId, UpdateOrganizationMemberRoleRequest request);

    void removeOrganizationMember(User currentUser, UUID organizationId, UUID memberId);
}

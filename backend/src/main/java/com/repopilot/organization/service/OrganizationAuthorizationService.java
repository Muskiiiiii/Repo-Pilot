package com.repopilot.organization.service;

import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.user.entity.User;

import java.util.List;
import java.util.UUID;

public interface OrganizationAuthorizationService {

    Organization getAuthorizedOrganization(UUID orgId, User user);

    OrganizationRole getUserRoleInOrganization(UUID orgId, User user);

    boolean hasAccess(UUID orgId, User user);

    void requireMinimumRole(UUID orgId, User user, OrganizationRole minimumRole);

    List<Organization> getUserOrganizations(User user);
}

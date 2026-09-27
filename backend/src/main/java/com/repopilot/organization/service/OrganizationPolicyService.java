package com.repopilot.organization.service;

import com.repopilot.organization.dto.OrganizationPolicyDto;
import com.repopilot.organization.dto.UpdateOrganizationPolicyRequest;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface OrganizationPolicyService {

    OrganizationPolicyDto getPolicy(UUID orgId, User user);

    OrganizationPolicyDto updatePolicy(UUID orgId, User user, UpdateOrganizationPolicyRequest request);
}

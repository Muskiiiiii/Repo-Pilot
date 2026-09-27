package com.repopilot.llm.service;

import com.repopilot.llm.dto.*;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface LlmPolicyAdministrationService {

    ExceptionalPolicyResponse createExceptionalPolicyVersion(UUID orgId, User caller, CreateExceptionalPolicyRequest request);

    PagedPolicyResponse<ExceptionalPolicyResponse> getExceptionalPolicyVersions(UUID orgId, User caller, int page, int size);

    ExceptionalPolicyResponse getSpecificPolicyVersion(UUID orgId, UUID policyId, User caller);

    ActiveExceptionalPolicyResponse getActivePointer(UUID orgId, String sourceAuthorityType, String originalProviderId, String targetProviderId, User caller);

    ActiveExceptionalPolicyResponse activatePolicyVersion(UUID orgId, UUID policyId, User admin);

    void deactivateActivePolicy(UUID orgId, String sourceAuthorityType, String originalProviderId, String targetProviderId, User admin);
}

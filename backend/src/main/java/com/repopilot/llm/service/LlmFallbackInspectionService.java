package com.repopilot.llm.service;

import com.repopilot.llm.dto.*;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface LlmFallbackInspectionService {

    LogicalRequestInspectionResponse getLogicalRequest(UUID orgId, UUID logicalRequestId, User caller);

    PagedPolicyResponse<ProviderAttemptInspectionResponse> getAttempts(UUID orgId, UUID logicalRequestId, int page, int size, User caller);

    PagedPolicyResponse<FallbackDecisionInspectionResponse> getFallbackDecisions(UUID orgId, UUID logicalRequestId, int page, int size, User caller);

    LlmExecutionTreeResponse getExecutionTree(UUID orgId, UUID logicalRequestId, User caller);
}

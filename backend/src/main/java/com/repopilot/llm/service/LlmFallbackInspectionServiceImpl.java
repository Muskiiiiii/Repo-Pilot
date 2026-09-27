package com.repopilot.llm.service;

import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.*;
import com.repopilot.llm.entity.LlmFallbackDecision;
import com.repopilot.llm.entity.LlmLogicalRequest;
import com.repopilot.llm.entity.LlmProviderAttempt;
import com.repopilot.llm.repository.LlmFallbackDecisionRepository;
import com.repopilot.llm.repository.LlmLogicalRequestRepository;
import com.repopilot.llm.repository.LlmProviderAttemptRepository;
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

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class LlmFallbackInspectionServiceImpl implements LlmFallbackInspectionService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_EXECUTION_TREE_NODES = 100;

    private final LlmLogicalRequestRepository logicalRequestRepository;
    private final LlmProviderAttemptRepository providerAttemptRepository;
    private final LlmFallbackDecisionRepository fallbackDecisionRepository;
    private final OrganizationAuthorizationService organizationAuthorizationService;

    @Autowired
    public LlmFallbackInspectionServiceImpl(LlmLogicalRequestRepository logicalRequestRepository,
                                            LlmProviderAttemptRepository providerAttemptRepository,
                                            LlmFallbackDecisionRepository fallbackDecisionRepository,
                                            OrganizationAuthorizationService organizationAuthorizationService) {
        this.logicalRequestRepository = logicalRequestRepository;
        this.providerAttemptRepository = providerAttemptRepository;
        this.fallbackDecisionRepository = fallbackDecisionRepository;
        this.organizationAuthorizationService = organizationAuthorizationService;
    }

    @Override
    public LogicalRequestInspectionResponse getLogicalRequest(UUID orgId, UUID logicalRequestId, User caller) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_MEMBER);
        LlmLogicalRequest logicalRequest = findAuthorizedLogicalRequest(orgId, logicalRequestId);
        return LogicalRequestInspectionResponse.fromEntity(logicalRequest);
    }

    @Override
    public PagedPolicyResponse<ProviderAttemptInspectionResponse> getAttempts(UUID orgId, UUID logicalRequestId, int page, int size, User caller) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_MEMBER);
        findAuthorizedLogicalRequest(orgId, logicalRequestId);

        int clampedPage = Math.max(0, page);
        int clampedSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        Pageable pageable = PageRequest.of(clampedPage, clampedSize);
        Page<LlmProviderAttempt> attemptPage = providerAttemptRepository.findByLogicalRequestIdOrderByAttemptNumberAsc(logicalRequestId, pageable);

        List<ProviderAttemptInspectionResponse> content = attemptPage.getContent().stream()
                .map(ProviderAttemptInspectionResponse::fromEntity)
                .collect(Collectors.toList());

        return new PagedPolicyResponse<>(content, attemptPage.getNumber(), attemptPage.getSize(), attemptPage.getTotalElements(), attemptPage.getTotalPages());
    }

    @Override
    public PagedPolicyResponse<FallbackDecisionInspectionResponse> getFallbackDecisions(UUID orgId, UUID logicalRequestId, int page, int size, User caller) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_MEMBER);
        findAuthorizedLogicalRequest(orgId, logicalRequestId);

        int clampedPage = Math.max(0, page);
        int clampedSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        Pageable pageable = PageRequest.of(clampedPage, clampedSize);
        Page<LlmFallbackDecision> decisionPage = fallbackDecisionRepository.findByLogicalRequestIdOrderByDecisionSequenceAsc(logicalRequestId, pageable);

        List<FallbackDecisionInspectionResponse> content = decisionPage.getContent().stream()
                .map(FallbackDecisionInspectionResponse::fromEntity)
                .collect(Collectors.toList());

        return new PagedPolicyResponse<>(content, decisionPage.getNumber(), decisionPage.getSize(), decisionPage.getTotalElements(), decisionPage.getTotalPages());
    }

    @Override
    public LlmExecutionTreeResponse getExecutionTree(UUID orgId, UUID logicalRequestId, User caller) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_MEMBER);
        LlmLogicalRequest logicalRequest = findAuthorizedLogicalRequest(orgId, logicalRequestId);

        List<LlmProviderAttempt> attempts = providerAttemptRepository.findByLogicalRequestIdOrderByAttemptNumberAsc(logicalRequestId);
        List<LlmFallbackDecision> decisions = fallbackDecisionRepository.findByLogicalRequestIdOrderByDecisionSequenceAsc(logicalRequestId);

        int totalNodesCount = attempts.size() + decisions.size();
        if (totalNodesCount > MAX_EXECUTION_TREE_NODES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Execution tree node count exceeds maximum allowed limit of " + MAX_EXECUTION_TREE_NODES);
        }

        Map<UUID, List<LlmFallbackDecision>> decisionsBySourceAttempt = new HashMap<>();
        for (LlmFallbackDecision decision : decisions) {
            if (decision.getSourceAttempt() != null) {
                decisionsBySourceAttempt.computeIfAbsent(decision.getSourceAttempt().getId(), k -> new ArrayList<>()).add(decision);
            }
        }

        List<LlmExecutionTreeResponse.ExecutionTreeNode> treeNodes = new ArrayList<>();
        for (LlmProviderAttempt attempt : attempts) {
            ProviderAttemptInspectionResponse attemptDto = ProviderAttemptInspectionResponse.fromEntity(attempt);
            List<LlmFallbackDecision> attemptDecisions = decisionsBySourceAttempt.getOrDefault(attempt.getId(), Collections.emptyList());

            List<LlmExecutionTreeResponse.ExecutionTreeDecisionNode> decisionNodes = new ArrayList<>();
            for (LlmFallbackDecision decision : attemptDecisions) {
                FallbackDecisionInspectionResponse decisionDto = FallbackDecisionInspectionResponse.fromEntity(decision);
                ProviderAttemptInspectionResponse targetAttemptDto = decision.getTargetAttempt() != null ? ProviderAttemptInspectionResponse.fromEntity(decision.getTargetAttempt()) : null;
                decisionNodes.add(new LlmExecutionTreeResponse.ExecutionTreeDecisionNode(decisionDto, targetAttemptDto));
            }

            treeNodes.add(new LlmExecutionTreeResponse.ExecutionTreeNode(attemptDto, decisionNodes));
        }

        LogicalRequestInspectionResponse requestDto = LogicalRequestInspectionResponse.fromEntity(logicalRequest);
        return new LlmExecutionTreeResponse(requestDto, treeNodes, totalNodesCount);
    }

    private LlmLogicalRequest findAuthorizedLogicalRequest(UUID orgId, UUID logicalRequestId) {
        return logicalRequestRepository.findByIdAndOrganizationId(logicalRequestId, orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Logical request not found for organization"));
    }
}

package com.repopilot.approval.controller;

import com.repopilot.approval.dto.GitPullRequestResult;
import com.repopilot.approval.dto.HumanApprovalCreateRequest;
import com.repopilot.approval.dto.HumanApprovalResponseDto;
import com.repopilot.approval.service.GitPublicationRecoveryBoundary;
import com.repopilot.approval.service.HumanApprovalService;
import com.repopilot.auth.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}")
public class HumanApprovalController {

    private final HumanApprovalService humanApprovalService;
    private final GitPublicationRecoveryBoundary gitPublicationRecoveryBoundary;

    public HumanApprovalController(
            HumanApprovalService humanApprovalService,
            GitPublicationRecoveryBoundary gitPublicationRecoveryBoundary
    ) {
        this.humanApprovalService = humanApprovalService;
        this.gitPublicationRecoveryBoundary = gitPublicationRecoveryBoundary;
    }

    @PostMapping("/code-changes/{codeChangeId}/approvals")
    public ResponseEntity<HumanApprovalResponseDto> createApproval(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID codeChangeId,
            @RequestParam(required = false) UUID reviewId,
            @Valid @RequestBody(required = false) HumanApprovalCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        HumanApprovalCreateRequest safeRequest = request != null ? request : new HumanApprovalCreateRequest();
        HumanApprovalResponseDto response = humanApprovalService.createApproval(
                projectId, repositoryId, taskId, codeChangeId, reviewId, safeRequest, currentUser.getUser()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/approvals/{approvalId}")
    public ResponseEntity<HumanApprovalResponseDto> getApproval(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID approvalId,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        HumanApprovalResponseDto response = humanApprovalService.getApprovalById(projectId, approvalId, currentUser.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/code-changes/{codeChangeId}/approvals")
    public ResponseEntity<List<HumanApprovalResponseDto>> getApprovalsForCodeChange(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID codeChangeId,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        List<HumanApprovalResponseDto> responses = humanApprovalService.getApprovalsForCodeChange(projectId, codeChangeId, currentUser.getUser());
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/approvals/{approvalId}/revoke")
    public ResponseEntity<HumanApprovalResponseDto> revokeApproval(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID approvalId,
            @RequestBody Map<String, String> payload,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        String reason = payload != null ? payload.getOrDefault("reason", "Revoked via UI") : "Revoked via UI";
        HumanApprovalResponseDto response = humanApprovalService.revokeApproval(projectId, approvalId, reason, currentUser.getUser());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/code-changes/{codeChangeId}/approvals/{approvalId}/publish")
    public ResponseEntity<GitPullRequestResult> publishOrRecoverApproval(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID codeChangeId,
            @PathVariable UUID approvalId,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        GitPullRequestResult result = gitPublicationRecoveryBoundary.recoverOrPublish(
                projectId, repositoryId, taskId, codeChangeId, approvalId, currentUser.getUser()
        );
        if (result.isCreated()) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        }
    }
}

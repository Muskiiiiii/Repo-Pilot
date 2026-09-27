package com.repopilot.approval.controller;

import com.repopilot.approval.dto.PublicationAuditEventResponseDto;
import com.repopilot.approval.service.PublicationAuditService;
import com.repopilot.auth.security.UserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}")
public class PublicationAuditController {

    private final PublicationAuditService publicationAuditService;

    public PublicationAuditController(PublicationAuditService publicationAuditService) {
        this.publicationAuditService = publicationAuditService;
    }

    @GetMapping("/audit")
    public ResponseEntity<List<PublicationAuditEventResponseDto>> getTaskAuditTrail(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        List<PublicationAuditEventResponseDto> auditTrail = publicationAuditService.getTaskAuditEvents(
                projectId, repositoryId, taskId, currentUser.getUser()
        );
        return ResponseEntity.ok(auditTrail);
    }

    @GetMapping("/code-changes/{codeChangeId}/audit")
    public ResponseEntity<List<PublicationAuditEventResponseDto>> getCodeChangeAuditTrail(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID codeChangeId,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        List<PublicationAuditEventResponseDto> auditTrail = publicationAuditService.getCodeChangeAuditEvents(
                projectId, codeChangeId, currentUser.getUser()
        );
        return ResponseEntity.ok(auditTrail);
    }
}

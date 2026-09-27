package com.repopilot.llm.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.AuditOutboxInspectionResponse;
import com.repopilot.llm.dto.PagedPolicyResponse;
import com.repopilot.llm.service.AuditOutboxInspectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/llm/audit-outbox")
public class AuditOutboxInspectionController {

    private final AuditOutboxInspectionService auditOutboxInspectionService;

    @Autowired
    public AuditOutboxInspectionController(AuditOutboxInspectionService auditOutboxInspectionService) {
        this.auditOutboxInspectionService = auditOutboxInspectionService;
    }

    @GetMapping
    public ResponseEntity<PagedPolicyResponse<AuditOutboxInspectionResponse>> listAuditOutboxEvents(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        requireAuthentication(principal);
        PagedPolicyResponse<AuditOutboxInspectionResponse> response = auditOutboxInspectionService.getAuditOutboxEvents(orgId, page, size, principal.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<AuditOutboxInspectionResponse> getAuditOutboxEvent(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @PathVariable("eventId") UUID eventId) {
        requireAuthentication(principal);
        AuditOutboxInspectionResponse response = auditOutboxInspectionService.getAuditOutboxEvent(orgId, eventId, principal.getUser());
        return ResponseEntity.ok(response);
    }

    private void requireAuthentication(UserPrincipal principal) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
    }
}

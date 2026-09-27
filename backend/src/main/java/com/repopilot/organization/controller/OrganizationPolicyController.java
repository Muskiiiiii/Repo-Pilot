package com.repopilot.organization.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.organization.dto.OrganizationPolicyDto;
import com.repopilot.organization.dto.UpdateOrganizationPolicyRequest;
import com.repopilot.organization.service.OrganizationPolicyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/policies")
public class OrganizationPolicyController {

    private final OrganizationPolicyService organizationPolicyService;

    public OrganizationPolicyController(OrganizationPolicyService organizationPolicyService) {
        this.organizationPolicyService = organizationPolicyService;
    }

    @GetMapping
    public ResponseEntity<OrganizationPolicyDto> getPolicy(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        OrganizationPolicyDto policy = organizationPolicyService.getPolicy(orgId, principal.getUser());
        return ResponseEntity.ok(policy);
    }

    @PutMapping
    public ResponseEntity<OrganizationPolicyDto> updatePolicy(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @Valid @RequestBody UpdateOrganizationPolicyRequest request) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        OrganizationPolicyDto policy = organizationPolicyService.updatePolicy(orgId, principal.getUser(), request);
        return ResponseEntity.ok(policy);
    }
}

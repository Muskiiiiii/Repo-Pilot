package com.repopilot.organization.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.organization.dto.*;
import com.repopilot.organization.service.OrganizationService;
import com.repopilot.project.dto.ProjectDto;
import com.repopilot.project.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationService organizationService;
    private final ProjectService projectService;

    public OrganizationController(OrganizationService organizationService, ProjectService projectService) {
        this.organizationService = organizationService;
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<OrganizationDto> createOrganization(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateOrganizationRequest request) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        OrganizationDto org = organizationService.createOrganization(principal.getUser(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(org);
    }

    @GetMapping
    public ResponseEntity<List<OrganizationDto>> getUserOrganizations(
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        List<OrganizationDto> orgs = organizationService.getUserOrganizations(principal.getUser());
        return ResponseEntity.ok(orgs);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationDto> getOrganizationById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID orgId) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        OrganizationDto org = organizationService.getOrganizationById(orgId, principal.getUser());
        return ResponseEntity.ok(org);
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<List<OrganizationMemberDto>> getOrganizationMembers(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID organizationId) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        List<OrganizationMemberDto> members = organizationService.getOrganizationMembers(principal.getUser(), organizationId);
        return ResponseEntity.ok(members);
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<OrganizationMemberDto> addOrganizationMember(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID organizationId,
            @Valid @RequestBody AddOrgMemberRequest request) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        OrganizationMemberDto member = organizationService.addOrganizationMember(principal.getUser(), organizationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(member);
    }

    @PutMapping("/{id}/members/{memberId}")
    public ResponseEntity<OrganizationMemberDto> updateOrganizationMemberRole(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID organizationId,
            @PathVariable("memberId") UUID memberId,
            @Valid @RequestBody UpdateOrganizationMemberRoleRequest request) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        OrganizationMemberDto member = organizationService.updateOrganizationMemberRole(principal.getUser(), organizationId, memberId, request);
        return ResponseEntity.ok(member);
    }

    @DeleteMapping("/{id}/members/{memberId}")
    public ResponseEntity<Map<String, String>> removeOrganizationMember(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID organizationId,
            @PathVariable("memberId") UUID memberId) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        organizationService.removeOrganizationMember(principal.getUser(), organizationId, memberId);
        return ResponseEntity.ok(Map.of("message", "Organization member removed successfully"));
    }

    @GetMapping("/{id}/projects")
    public ResponseEntity<List<ProjectDto>> getOrganizationProjects(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID organizationId) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        List<ProjectDto> projects = projectService.getOrganizationProjects(principal.getUser(), organizationId);
        return ResponseEntity.ok(projects);
    }
}

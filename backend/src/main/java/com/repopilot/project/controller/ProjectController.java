package com.repopilot.project.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.project.dto.*;
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
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ProjectDto> createProject(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateProjectRequest request) {
        ProjectDto project = projectService.createProject(principal.getUser(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(project);
    }

    @GetMapping
    public ResponseEntity<List<ProjectDto>> getUserProjects(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String query) {
        List<ProjectDto> projects = projectService.getUserProjects(principal.getUser(), query);
        return ResponseEntity.ok(projects);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectDto> getProjectById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID projectId) {
        ProjectDto project = projectService.getProjectById(projectId, principal.getUser());
        return ResponseEntity.ok(project);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectDto> updateProject(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request) {
        ProjectDto project = projectService.updateProject(projectId, principal.getUser(), request);
        return ResponseEntity.ok(project);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteProject(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID projectId) {
        projectService.deleteProject(projectId, principal.getUser());
        return ResponseEntity.ok(Map.of("message", "Project deleted successfully"));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<List<ProjectMemberDto>> getProjectMembers(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID projectId) {
        List<ProjectMemberDto> members = projectService.getProjectMembers(projectId, principal.getUser());
        return ResponseEntity.ok(members);
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<ProjectMemberDto> addProjectMember(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID projectId,
            @Valid @RequestBody AddMemberRequest request) {
        ProjectMemberDto member = projectService.addProjectMember(projectId, principal.getUser(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(member);
    }

    @DeleteMapping("/{id}/members/{memberId}")
    public ResponseEntity<Map<String, String>> removeProjectMember(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID projectId,
            @PathVariable("memberId") UUID memberId) {
        projectService.removeProjectMember(projectId, memberId, principal.getUser());
        return ResponseEntity.ok(Map.of("message", "Member removed successfully"));
    }
}

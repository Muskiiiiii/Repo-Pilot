package com.repopilot.github.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.github.dto.*;
import com.repopilot.github.service.GithubService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories")
public class ProjectRepositoryController {

    private final GithubService githubService;

    public ProjectRepositoryController(GithubService githubService) {
        this.githubService = githubService;
    }

    @GetMapping
    public ResponseEntity<List<ProjectRepositoryDto>> getProjectRepositories(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("projectId") UUID projectId) {
        List<ProjectRepositoryDto> repos = githubService.getProjectRepositories(principal.getUser(), projectId);
        return ResponseEntity.ok(repos);
    }

    @PostMapping("/import")
    public ResponseEntity<ProjectRepositoryDto> importRepository(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody ImportRepositoryRequest request) {
        ProjectRepositoryDto repo = githubService.importRepositoryToProject(principal.getUser(), projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(repo);
    }

    @GetMapping("/{repositoryId}")
    public ResponseEntity<ProjectRepositoryDto> getProjectRepositoryDetails(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("repositoryId") UUID repositoryId) {
        ProjectRepositoryDto repo = githubService.getProjectRepositoryDetails(principal.getUser(), projectId, repositoryId);
        return ResponseEntity.ok(repo);
    }

    @GetMapping("/{repositoryId}/branches")
    public ResponseEntity<List<BranchDto>> getRepositoryBranches(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("repositoryId") UUID repositoryId) {
        List<BranchDto> branches = githubService.getRepositoryBranches(principal.getUser(), projectId, repositoryId);
        return ResponseEntity.ok(branches);
    }

    @GetMapping("/{repositoryId}/files")
    public ResponseEntity<List<FileTreeItemDto>> getRepositoryFileTree(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("repositoryId") UUID repositoryId,
            @RequestParam(name = "branch", required = false) String branch) {
        List<FileTreeItemDto> tree = githubService.getRepositoryFileTree(principal.getUser(), projectId, repositoryId, branch);
        return ResponseEntity.ok(tree);
    }

    @GetMapping("/{repositoryId}/files/content")
    public ResponseEntity<FileContentDto> getRepositoryFileContent(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("repositoryId") UUID repositoryId,
            @RequestParam(name = "branch", required = false) String branch,
            @RequestParam(name = "path") String path) {
        FileContentDto content = githubService.getRepositoryFileContent(principal.getUser(), projectId, repositoryId, branch, path);
        return ResponseEntity.ok(content);
    }
}

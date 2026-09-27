package com.repopilot.repository.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.repository.dto.RepositoryIndexProgressDto;
import com.repopilot.repository.service.RepositoryIndexingService;
import com.repopilot.user.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/index")
public class RepositoryIndexController {

    private final RepositoryIndexingService repositoryIndexingService;

    public RepositoryIndexController(RepositoryIndexingService repositoryIndexingService) {
        this.repositoryIndexingService = repositoryIndexingService;
    }

    @PostMapping
    public ResponseEntity<RepositoryIndexProgressDto> triggerIndexing(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @RequestParam(required = false) String branch,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        User user = currentUser.getUser();
        RepositoryIndexProgressDto progress = repositoryIndexingService.triggerIndexing(projectId, repositoryId, user, branch);
        return ResponseEntity.ok(progress);
    }

    @PostMapping("/stop")
    public ResponseEntity<RepositoryIndexProgressDto> stopIndexing(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        User user = currentUser.getUser();
        RepositoryIndexProgressDto progress = repositoryIndexingService.stopIndexing(projectId, repositoryId, user);
        return ResponseEntity.ok(progress);
    }

    @PostMapping("/resume")
    public ResponseEntity<RepositoryIndexProgressDto> resumeIndexing(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        User user = currentUser.getUser();
        RepositoryIndexProgressDto progress = repositoryIndexingService.resumeIndexing(projectId, repositoryId, user);
        return ResponseEntity.ok(progress);
    }

    @GetMapping("/status")
    public ResponseEntity<RepositoryIndexProgressDto> getIndexingStatus(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        User user = currentUser.getUser();
        RepositoryIndexProgressDto status = repositoryIndexingService.getIndexingStatus(projectId, repositoryId, user);
        return ResponseEntity.ok(status);
    }
}

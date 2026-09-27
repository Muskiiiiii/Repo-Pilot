package com.repopilot.repository.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.repository.dto.SearchRequestDto;
import com.repopilot.repository.dto.SearchResponseDto;
import com.repopilot.repository.service.SemanticSearchService;
import com.repopilot.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/search")
public class RepositorySearchController {

    private final SemanticSearchService semanticSearchService;

    public RepositorySearchController(SemanticSearchService semanticSearchService) {
        this.semanticSearchService = semanticSearchService;
    }

    @PostMapping
    public ResponseEntity<SearchResponseDto> search(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @Valid @RequestBody SearchRequestDto request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        User user = currentUser.getUser();
        SearchResponseDto response = semanticSearchService.searchRepository(projectId, repositoryId, request, user);
        return ResponseEntity.ok(response);
    }
}

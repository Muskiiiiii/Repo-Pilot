package com.repopilot.change.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.change.dto.CodeChangeResponseDto;
import com.repopilot.change.dto.CodeChangeSetResponseDto;
import com.repopilot.change.service.CodeGenerationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}")
public class CodeChangeController {

    private final CodeGenerationService codeGenerationService;

    public CodeChangeController(CodeGenerationService codeGenerationService) {
        this.codeGenerationService = codeGenerationService;
    }

    @PostMapping("/plans/{planId}/generate-code")
    public ResponseEntity<CodeChangeSetResponseDto> generateCode(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID planId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        CodeChangeSetResponseDto response = codeGenerationService.generateCode(projectId, repositoryId, taskId, planId, currentUser.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/code-changes")
    public ResponseEntity<List<CodeChangeResponseDto>> getCodeChanges(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        List<CodeChangeResponseDto> response = codeGenerationService.getCodeChanges(projectId, repositoryId, taskId, currentUser.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/code-changes/{changeId}")
    public ResponseEntity<CodeChangeResponseDto> getCodeChangeById(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID changeId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        CodeChangeResponseDto response = codeGenerationService.getCodeChangeById(projectId, repositoryId, taskId, changeId, currentUser.getUser());
        return ResponseEntity.ok(response);
    }
}

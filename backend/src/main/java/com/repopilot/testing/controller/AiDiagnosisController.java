package com.repopilot.testing.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.testing.dto.AIDiagnosisResponseDto;
import com.repopilot.testing.service.AiDiagnosisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/executions/{executionId}/repair-iterations/{iterationId}")
public class AiDiagnosisController {

    private final AiDiagnosisService aiDiagnosisService;

    @Autowired
    public AiDiagnosisController(AiDiagnosisService aiDiagnosisService) {
        this.aiDiagnosisService = aiDiagnosisService;
    }

    @PostMapping("/diagnose")
    public ResponseEntity<AIDiagnosisResponseDto> diagnoseIteration(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID executionId,
            @PathVariable UUID iterationId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        AIDiagnosisResponseDto response = aiDiagnosisService.diagnoseIteration(
                projectId, repositoryId, taskId, executionId, iterationId, currentUserPrincipal.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/diagnosis")
    public ResponseEntity<AIDiagnosisResponseDto> getDiagnosis(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID executionId,
            @PathVariable UUID iterationId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        AIDiagnosisResponseDto response = aiDiagnosisService.getDiagnosis(
                projectId, repositoryId, taskId, executionId, iterationId, currentUserPrincipal.getUser());
        return ResponseEntity.ok(response);
    }
}

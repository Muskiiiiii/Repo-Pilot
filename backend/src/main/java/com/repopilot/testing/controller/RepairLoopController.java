package com.repopilot.testing.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.testing.dto.RepairLoopRequestDto;
import com.repopilot.testing.dto.RepairLoopResponseDto;
import com.repopilot.testing.dto.RepairLoopStatusResponseDto;
import com.repopilot.testing.service.RepairLoopApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/executions/{executionId}/repair")
public class RepairLoopController {

    private final RepairLoopApiService repairLoopApiService;

    @Autowired
    public RepairLoopController(RepairLoopApiService repairLoopApiService) {
        this.repairLoopApiService = repairLoopApiService;
    }

    @PostMapping
    public ResponseEntity<RepairLoopResponseDto> triggerRepairLoop(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID executionId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKeyHeader,
            @RequestBody(required = false) RepairLoopRequestDto request,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (request == null) {
            request = new RepairLoopRequestDto();
        }
        if (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()) {
            request.setIdempotencyKey(idempotencyKeyHeader.trim());
        }

        RepairLoopResponseDto response = repairLoopApiService.triggerRepairLoop(
                projectId, repositoryId, taskId, executionId, request, currentUserPrincipal.getUser());

        HttpStatus status = response.isAlreadyCompleted() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping
    public ResponseEntity<RepairLoopStatusResponseDto> getRepairLoopStatus(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID executionId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        RepairLoopStatusResponseDto response = repairLoopApiService.getRepairLoopStatus(
                projectId, repositoryId, taskId, executionId, currentUserPrincipal.getUser());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/cancel")
    public ResponseEntity<RepairLoopResponseDto> cancelRepairLoop(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID executionId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        RepairLoopResponseDto response = repairLoopApiService.cancelRepairLoop(
                projectId, repositoryId, taskId, executionId, currentUserPrincipal.getUser());

        return ResponseEntity.ok(response);
    }
}

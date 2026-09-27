package com.repopilot.testing.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.testing.dto.TaskExecutionRequestDto;
import com.repopilot.testing.dto.TaskExecutionResponseDto;
import com.repopilot.testing.service.TaskExecutionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/executions")
public class TaskExecutionController {

    private final TaskExecutionService taskExecutionService;

    @Autowired
    public TaskExecutionController(TaskExecutionService taskExecutionService) {
        this.taskExecutionService = taskExecutionService;
    }

    @PostMapping
    public ResponseEntity<TaskExecutionResponseDto> executeTask(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @RequestBody(required = false) TaskExecutionRequestDto request,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        TaskExecutionResponseDto response = taskExecutionService.executeTask(
                projectId, repositoryId, taskId, request, currentUserPrincipal.getUser());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<TaskExecutionResponseDto> getExecutionStatus(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID executionId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        TaskExecutionResponseDto response = taskExecutionService.getExecutionStatus(
                projectId, repositoryId, taskId, executionId, currentUserPrincipal.getUser());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{executionId}/cancel")
    public ResponseEntity<TaskExecutionResponseDto> cancelExecution(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID executionId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        TaskExecutionResponseDto response = taskExecutionService.cancelExecution(
                projectId, repositoryId, taskId, executionId, currentUserPrincipal.getUser());
        return ResponseEntity.ok(response);
    }
}

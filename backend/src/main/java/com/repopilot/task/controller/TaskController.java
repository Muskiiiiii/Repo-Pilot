package com.repopilot.task.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.task.dto.PlanResponseDto;
import com.repopilot.task.dto.TaskCreateRequestDto;
import com.repopilot.task.dto.TaskResponseDto;
import com.repopilot.task.service.TaskPlanningService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks")
public class TaskController {

    private final TaskPlanningService taskPlanningService;

    public TaskController(TaskPlanningService taskPlanningService) {
        this.taskPlanningService = taskPlanningService;
    }

    @PostMapping
    public ResponseEntity<TaskResponseDto> createTask(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @Valid @RequestBody TaskCreateRequestDto request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        TaskResponseDto response = taskPlanningService.createTask(projectId, repositoryId, request, currentUser.getUser());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskResponseDto> getTask(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        TaskResponseDto response = taskPlanningService.getTask(projectId, repositoryId, taskId, currentUser.getUser());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{taskId}/plan")
    public ResponseEntity<PlanResponseDto> generatePlan(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        PlanResponseDto response = taskPlanningService.generatePlan(projectId, repositoryId, taskId, currentUser.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{taskId}/plan")
    public ResponseEntity<PlanResponseDto> getPlan(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        PlanResponseDto response = taskPlanningService.getPlan(projectId, repositoryId, taskId, currentUser.getUser());
        return ResponseEntity.ok(response);
    }
}

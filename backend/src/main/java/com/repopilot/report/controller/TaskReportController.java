package com.repopilot.report.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.report.dto.TaskReportResponseDto;
import com.repopilot.report.service.ImplementationReportService;
import com.repopilot.user.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class TaskReportController {

    private final ImplementationReportService implementationReportService;

    @Autowired
    public TaskReportController(ImplementationReportService implementationReportService) {
        this.implementationReportService = implementationReportService;
    }

    @GetMapping("/tasks/{taskId}/report")
    public ResponseEntity<TaskReportResponseDto> getTaskReport(
            @PathVariable UUID projectId,
            @PathVariable UUID taskId,
            @AuthenticationPrincipal UserPrincipal principal) {
        User currentUser = (principal != null) ? principal.getUser() : null;
        TaskReportResponseDto dto = implementationReportService.getTaskReport(projectId, null, taskId, currentUser);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/repositories/{repositoryId}/tasks/{taskId}/report")
    public ResponseEntity<TaskReportResponseDto> getTaskReportWithRepo(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @AuthenticationPrincipal UserPrincipal principal) {
        User currentUser = (principal != null) ? principal.getUser() : null;
        TaskReportResponseDto dto = implementationReportService.getTaskReport(projectId, repositoryId, taskId, currentUser);
        return ResponseEntity.ok(dto);
    }
}

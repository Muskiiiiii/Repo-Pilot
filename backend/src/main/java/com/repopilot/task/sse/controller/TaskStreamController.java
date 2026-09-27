package com.repopilot.task.sse.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.task.sse.service.SseTaskStreamEngine;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskStreamController {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final SseTaskStreamEngine sseTaskStreamEngine;

    public TaskStreamController(TaskRepository taskRepository,
                                ProjectRepository projectRepository,
                                ProjectAuthorizationService projectAuthorizationService,
                                SseTaskStreamEngine sseTaskStreamEngine) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.projectAuthorizationService = projectAuthorizationService;
        this.sseTaskStreamEngine = sseTaskStreamEngine;
    }

    @GetMapping(value = "/{taskId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTaskEvents(
            @PathVariable UUID taskId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
            @AuthenticationPrincipal UserPrincipal currentUser,
            HttpServletRequest request) {

        if (currentUser == null || currentUser.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required to open task event stream");
        }

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Task not found: " + taskId));

        if (task.getProject() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Task project not resolved for task: " + taskId);
        }

        Project project = projectRepository.findById(task.getProject().getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Project not found: " + task.getProject().getId()));

        // Authorize caller against project (minimum role VIEWER required)
        projectAuthorizationService.requireMinimumRole(project, currentUser.getUser(), ProjectRole.VIEWER);

        String clientIp = request.getRemoteAddr();
        return sseTaskStreamEngine.createTaskStream(taskId, currentUser.getUser(), project, task, lastEventIdHeader, clientIp);
    }
}

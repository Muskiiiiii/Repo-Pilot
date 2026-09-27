package com.repopilot.task.service;

import com.repopilot.task.dto.PlanResponseDto;
import com.repopilot.task.dto.TaskCreateRequestDto;
import com.repopilot.task.dto.TaskResponseDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface TaskPlanningService {

    TaskResponseDto createTask(UUID projectId, UUID repositoryId, TaskCreateRequestDto request, User currentUser);

    TaskResponseDto getTask(UUID projectId, UUID repositoryId, UUID taskId, User currentUser);

    PlanResponseDto generatePlan(UUID projectId, UUID repositoryId, UUID taskId, User currentUser);

    PlanResponseDto getPlan(UUID projectId, UUID repositoryId, UUID taskId, User currentUser);
}

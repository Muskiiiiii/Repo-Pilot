package com.repopilot.testing.service;

import com.repopilot.testing.dto.TaskExecutionRequestDto;
import com.repopilot.testing.dto.TaskExecutionResponseDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface TaskExecutionService {

    TaskExecutionResponseDto executeTask(UUID projectId, UUID repositoryId, UUID taskId, TaskExecutionRequestDto request, User currentUser);

    TaskExecutionResponseDto getExecutionStatus(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, User currentUser);

    TaskExecutionResponseDto cancelExecution(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, User currentUser);
}

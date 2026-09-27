package com.repopilot.testing.service;

import com.repopilot.testing.dto.MultiIterationOrchestrationResponseDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface MultiIterationRepairOrchestrationService {

    MultiIterationOrchestrationResponseDto orchestrateSelfCorrection(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, User currentUser);
}

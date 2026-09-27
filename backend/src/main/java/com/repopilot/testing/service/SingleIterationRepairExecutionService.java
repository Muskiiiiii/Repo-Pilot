package com.repopilot.testing.service;

import com.repopilot.testing.dto.RepairExecutionResponseDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface SingleIterationRepairExecutionService {

    RepairExecutionResponseDto executeRepairIteration(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, UUID iterationId, User currentUser);
}

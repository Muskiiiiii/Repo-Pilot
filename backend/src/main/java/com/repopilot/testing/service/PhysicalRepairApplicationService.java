package com.repopilot.testing.service;

import com.repopilot.testing.dto.AIRepairProposalResponseDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface PhysicalRepairApplicationService {

    AIRepairProposalResponseDto applyPhysicalRepair(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, UUID iterationId, User currentUser);
}

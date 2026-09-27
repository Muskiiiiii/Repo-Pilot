package com.repopilot.testing.service;

import com.repopilot.testing.dto.AIRepairProposalResponseDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface AiRepairProposalService {

    AIRepairProposalResponseDto generateProposal(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, UUID iterationId, User currentUser);

    AIRepairProposalResponseDto getProposal(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, UUID iterationId, User currentUser);
}

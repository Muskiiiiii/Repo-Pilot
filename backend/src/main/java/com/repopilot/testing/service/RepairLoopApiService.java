package com.repopilot.testing.service;

import com.repopilot.testing.dto.RepairLoopRequestDto;
import com.repopilot.testing.dto.RepairLoopResponseDto;
import com.repopilot.testing.dto.RepairLoopStatusResponseDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface RepairLoopApiService {

    RepairLoopResponseDto triggerRepairLoop(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, RepairLoopRequestDto request, User currentUser);

    RepairLoopStatusResponseDto getRepairLoopStatus(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, User currentUser);

    RepairLoopResponseDto cancelRepairLoop(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, User currentUser);
}

package com.repopilot.testing.service;

import com.repopilot.testing.dto.AIDiagnosisResponseDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface AiDiagnosisService {

    AIDiagnosisResponseDto diagnoseIteration(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, UUID iterationId, User currentUser);

    AIDiagnosisResponseDto getDiagnosis(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, UUID iterationId, User currentUser);
}

package com.repopilot.change.service;

import com.repopilot.change.dto.CodeChangeResponseDto;
import com.repopilot.change.dto.CodeChangeSetResponseDto;
import com.repopilot.user.entity.User;

import java.util.List;
import java.util.UUID;

public interface CodeGenerationService {

    CodeChangeSetResponseDto generateCode(UUID projectId, UUID repositoryId, UUID taskId, UUID planId, User currentUser);

    List<CodeChangeResponseDto> getCodeChanges(UUID projectId, UUID repositoryId, UUID taskId, User currentUser);

    CodeChangeResponseDto getCodeChangeById(UUID projectId, UUID repositoryId, UUID taskId, UUID changeId, User currentUser);
}

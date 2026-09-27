package com.repopilot.repository.service;

import com.repopilot.repository.dto.SearchRequestDto;
import com.repopilot.repository.dto.SearchResponseDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface SemanticSearchService {
    SearchResponseDto searchRepository(UUID projectId, UUID repositoryId, SearchRequestDto request, User currentUser);
}

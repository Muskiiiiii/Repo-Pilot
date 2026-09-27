package com.repopilot.repository.service;

import com.repopilot.repository.dto.RepositoryIndexProgressDto;
import com.repopilot.repository.entity.Repository;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface RepositoryIndexingService {

    RepositoryIndexProgressDto triggerIndexing(UUID projectId, UUID repositoryId, User currentUser, String branch);

    RepositoryIndexProgressDto stopIndexing(UUID projectId, UUID repositoryId, User currentUser);

    RepositoryIndexProgressDto resumeIndexing(UUID projectId, UUID repositoryId, User currentUser);

    RepositoryIndexProgressDto getIndexingStatus(UUID projectId, UUID repositoryId, User currentUser);

    void executeIndexingProcess(User currentUser, Repository repository, String targetBranch, String lockKey);
}

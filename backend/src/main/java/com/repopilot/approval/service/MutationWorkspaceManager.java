package com.repopilot.approval.service;

import com.repopilot.approval.dto.MutationWorkspaceResult;
import com.repopilot.repository.entity.Repository;

import java.nio.file.Path;
import java.util.UUID;

public interface MutationWorkspaceManager {

    MutationWorkspaceResult createMutationWorkspace(
            UUID approvalId,
            UUID operationId,
            UUID projectId,
            Repository repository,
            UUID taskId
    );

    String calculateTreeDigest(Path directoryPath);

    void verifyMasterImmutability(Path masterPath, String initialDigest);

    void cleanupMutationWorkspace(UUID operationId);

    void sweepOrphanWorkspaces(long maxAgeMinutes);

    Path getMutationWorkspacePath(UUID operationId);

    void validateContainment(Path rootPath, Path childPath);
}

package com.repopilot.testing.service;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.repository.entity.Repository;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public interface WorkspaceManagerService {

    Path materializeWorkspace(UUID executionId, Repository repository);

    void applyCodeChangeSnapshot(UUID executionId, Path workspaceRepoDir, List<CodeChange> codeChanges);

    String calculateTreeDigest(Path directory);

    void cleanupWorkspace(UUID executionId);

    Path getWorkspaceRepoDir(UUID executionId);
}

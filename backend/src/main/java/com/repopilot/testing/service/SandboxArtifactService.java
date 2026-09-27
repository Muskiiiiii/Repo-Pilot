package com.repopilot.testing.service;

import com.repopilot.testing.entity.SandboxExecutionArtifact;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public interface SandboxArtifactService {

    /**
     * Fenced artifact publication: validates worker node ownership, lease token, and fencing version
     * against PostgreSQL before saving an artifact.
     */
    SandboxExecutionArtifact saveExecutionArtifact(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion,
                                                   String artifactType, String relativePath, String rawContent);

    /**
     * Collects and persists all artifacts from a completed container workspace directory in a fenced, path-safe manner.
     */
    List<SandboxExecutionArtifact> collectAndPersistWorkspaceArtifacts(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion,
                                                                        Path workspaceDir, int maxArtifacts, long maxTotalSizeBytes);

    /**
     * Retrieves all artifacts stored for a given job execution ID.
     */
    List<SandboxExecutionArtifact> getArtifactsForJob(UUID jobId);

    /**
     * Verifies that the SHA-256 content digest of an artifact matches expected hash.
     */
    boolean verifyArtifactChecksum(UUID artifactId, String expectedSha256);
}

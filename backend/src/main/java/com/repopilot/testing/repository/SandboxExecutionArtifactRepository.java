package com.repopilot.testing.repository;

import com.repopilot.testing.entity.SandboxExecutionArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SandboxExecutionArtifactRepository extends JpaRepository<SandboxExecutionArtifact, UUID> {

    List<SandboxExecutionArtifact> findByJobExecutionId(UUID jobExecutionId);
}

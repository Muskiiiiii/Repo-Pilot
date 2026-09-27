package com.repopilot.repository.repository;

import com.repopilot.project.entity.Project;
import com.repopilot.repository.entity.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface RepositoryRepository extends JpaRepository<Repository, UUID> {

    List<Repository> findByProject(Project project);

    List<Repository> findByProjectId(UUID projectId);

    Optional<Repository> findByProjectIdAndRepoName(UUID projectId, String repoName);
}

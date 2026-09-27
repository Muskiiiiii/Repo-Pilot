package com.repopilot.repository.repository;

import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface RepositoryFileRepository extends JpaRepository<RepositoryFile, UUID> {

    List<RepositoryFile> findByRepository(Repository repository);

    List<RepositoryFile> findByRepositoryId(UUID repositoryId);

    List<RepositoryFile> findByRepositoryIdAndBranchId(UUID repositoryId, UUID branchId);

    Optional<RepositoryFile> findByRepositoryIdAndPath(UUID repositoryId, String path);

    long countByRepositoryId(UUID repositoryId);
}

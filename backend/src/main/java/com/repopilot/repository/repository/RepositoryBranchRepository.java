package com.repopilot.repository.repository;

import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryBranch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface RepositoryBranchRepository extends JpaRepository<RepositoryBranch, UUID> {

    List<RepositoryBranch> findByRepository(Repository repository);

    List<RepositoryBranch> findByRepositoryId(UUID repositoryId);

    Optional<RepositoryBranch> findByRepositoryIdAndName(UUID repositoryId, String name);
}

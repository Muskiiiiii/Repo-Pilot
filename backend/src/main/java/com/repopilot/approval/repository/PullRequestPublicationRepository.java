package com.repopilot.approval.repository;

import com.repopilot.approval.entity.PullRequestPublication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PullRequestPublicationRepository extends JpaRepository<PullRequestPublication, UUID> {

    Optional<PullRequestPublication> findByApprovalId(UUID approvalId);

    Optional<PullRequestPublication> findByRepositoryIdAndBranchName(UUID repositoryId, String branchName);
}

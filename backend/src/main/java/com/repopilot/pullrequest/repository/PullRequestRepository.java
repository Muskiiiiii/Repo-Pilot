package com.repopilot.pullrequest.repository;

import com.repopilot.pullrequest.entity.PullRequest;
import com.repopilot.task.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PullRequestRepository extends JpaRepository<PullRequest, UUID> {

    List<PullRequest> findByTask(Task task);

    List<PullRequest> findByTaskId(UUID taskId);

    Optional<PullRequest> findByRepositoryIdAndGithubPrId(UUID repositoryId, Long githubPrId);
}

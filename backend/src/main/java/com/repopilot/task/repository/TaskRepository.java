package com.repopilot.task.repository;

import com.repopilot.project.entity.Project;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID> {

    List<Task> findByProject(Project project);

    List<Task> findByProjectId(UUID projectId);

    List<Task> findByProjectIdAndStatus(UUID projectId, TaskStatus status);

    java.util.Optional<Task> findByIdAndProjectIdAndRepositoryId(UUID id, UUID projectId, UUID repositoryId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE Task t SET t.updatedAt = CURRENT_TIMESTAMP WHERE t.id = :taskId AND t.generationToken = :token")
    int verifyGenerationTokenAtomic(@org.springframework.data.repository.query.Param("taskId") UUID taskId, @org.springframework.data.repository.query.Param("token") String token);
}

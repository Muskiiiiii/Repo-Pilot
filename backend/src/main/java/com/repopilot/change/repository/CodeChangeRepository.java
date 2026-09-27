package com.repopilot.change.repository;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.task.entity.Task;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CodeChangeRepository extends JpaRepository<CodeChange, UUID> {

    List<CodeChange> findByTask(Task task);

    List<CodeChange> findByTaskId(UUID taskId);

    List<CodeChange> findByTaskIdOrderByCreatedAtDesc(UUID taskId);

    List<CodeChange> findByPlanId(UUID planId);

    Optional<CodeChange> findByTaskIdAndId(UUID taskId, UUID id);

    @Query(value = "SELECT * FROM code_changes WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<CodeChange> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        SELECT c FROM CodeChange c
        WHERE c.task.id = :taskId
          AND c.repository.id = :repositoryId
          AND c.status NOT IN ('REJECTED', 'VALIDATION_FAILED')
        ORDER BY c.updatedAt DESC, c.id DESC
    """)
    List<CodeChange> findActiveCodeChangesForTaskAndRepository(
            @Param("taskId") UUID taskId,
            @Param("repositoryId") UUID repositoryId,
            org.springframework.data.domain.Pageable pageable
    );

    void deleteByTaskId(UUID taskId);
}

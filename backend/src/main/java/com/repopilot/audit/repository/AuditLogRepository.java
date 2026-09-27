package com.repopilot.audit.repository;

import com.repopilot.audit.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByUserId(UUID userId);

    List<AuditLog> findByProjectId(UUID projectId);

    List<AuditLog> findByTaskId(UUID taskId);

    List<AuditLog> findByResourceTypeAndResourceId(String resourceType, UUID resourceId);
}

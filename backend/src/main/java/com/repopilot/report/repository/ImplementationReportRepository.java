package com.repopilot.report.repository;

import com.repopilot.report.entity.ImplementationReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ImplementationReportRepository extends JpaRepository<ImplementationReport, UUID> {

    Optional<ImplementationReport> findByTaskExecutionId(UUID taskExecutionId);

    Optional<ImplementationReport> findByTaskId(UUID taskId);

    Optional<ImplementationReport> findByIdAndProjectId(UUID id, UUID projectId);

    boolean existsByTaskExecutionId(UUID taskExecutionId);
}

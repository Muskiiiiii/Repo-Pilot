package com.repopilot.report.repository;

import com.repopilot.report.entity.ReportSynthesis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportSynthesisRepository extends JpaRepository<ReportSynthesis, UUID> {

    Optional<ReportSynthesis> findByImplementationReportIdAndAiSynthesisInputHash(UUID implementationReportId, String aiSynthesisInputHash);

    Optional<ReportSynthesis> findByImplementationReportId(UUID implementationReportId);

    Optional<ReportSynthesis> findByTaskExecutionId(UUID taskExecutionId);

    boolean existsByImplementationReportIdAndAiSynthesisInputHash(UUID implementationReportId, String aiSynthesisInputHash);
}

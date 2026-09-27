package com.repopilot.llm.service;

import com.repopilot.llm.dto.AuditExportJobResponse;
import com.repopilot.llm.dto.CreateAuditExportRequest;
import com.repopilot.llm.entity.AuditExportJob;
import com.repopilot.llm.entity.AuditExportJobStatus;
import com.repopilot.user.entity.User;
import org.springframework.data.domain.Page;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface AuditExportJobEngineService {

    AuditExportJobResponse createExportJob(UUID orgId, User caller, CreateAuditExportRequest request);

    AuditExportJobResponse getExportJob(UUID orgId, UUID jobId, User caller);

    Page<AuditExportJobResponse> getExportJobs(UUID orgId, User caller, int page, int size);

    Optional<AuditExportJob> claimNextJob(String workerIdentity, Duration leaseDuration);

    boolean updateJobStatusFenced(UUID jobId, UUID leaseToken, Long fencingVersion, AuditExportJobStatus fromStatus, AuditExportJobStatus toStatus);

    boolean heartbeatLease(UUID jobId, UUID leaseToken, Long fencingVersion, Duration extendDuration);

    com.repopilot.llm.dto.ExportStreamingResult processJobExportStream(UUID orgId, UUID jobId, UUID leaseToken, Long fencingVersion, java.io.OutputStream outputStream);

    AuditExportJobResponse executeFullExportPipeline(UUID orgId, UUID jobId, String workerIdentity, UUID leaseToken, Long fencingVersion);

    AuditExportJobResponse rewrapExportKey(UUID orgId, UUID jobId, User caller);
}

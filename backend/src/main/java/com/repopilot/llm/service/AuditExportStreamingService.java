package com.repopilot.llm.service;

import com.repopilot.llm.dto.ExportStreamingResult;
import com.repopilot.llm.entity.AuditExportJob;
import com.repopilot.user.entity.User;

import java.io.OutputStream;
import java.util.UUID;

public interface AuditExportStreamingService {
    ExportStreamingResult processExportStream(AuditExportJob job, OutputStream targetOutputStream);
    ExportStreamingResult streamAuditExport(UUID orgId, UUID jobId, User caller, OutputStream targetOutputStream);
}

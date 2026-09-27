package com.repopilot.report.service;

import com.repopilot.report.dto.ReportSynthesisResponseDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface ReportSynthesizerService {

    ReportSynthesisResponseDto synthesizeReport(UUID executionId, User currentUser);

    ReportSynthesisResponseDto getSynthesisByExecutionId(UUID executionId, User currentUser);

    ReportSynthesisResponseDto getSynthesisByReportId(UUID reportId, User currentUser);

    String computeAiSynthesisInputHash(
            UUID reportId,
            UUID executionId,
            String sourceContextHash,
            String taskTitle,
            String executionOutcome,
            String changedFilesJson,
            String testSummaryJson,
            String securitySummaryJson,
            String confidenceScore,
            String failureReason,
            String synthesizerVersion,
            String promptVersion,
            String validatorVersion
    );
}

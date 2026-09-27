package com.repopilot.report.service;

import com.repopilot.report.dto.ImplementationReportResponseDto;
import com.repopilot.report.dto.TaskReportResponseDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface ImplementationReportService {

    ImplementationReportResponseDto generateReport(UUID executionId, User currentUser);

    ImplementationReportResponseDto getReportByExecutionId(UUID executionId, User currentUser);

    ImplementationReportResponseDto getReportById(UUID reportId, User currentUser);

    ImplementationReportResponseDto getReportByTaskId(UUID taskId, User currentUser);

    TaskReportResponseDto getTaskReport(UUID projectId, UUID repositoryId, UUID taskId, User currentUser);
}

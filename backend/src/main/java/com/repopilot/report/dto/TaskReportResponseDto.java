package com.repopilot.report.dto;

import java.util.UUID;

public class TaskReportResponseDto {

    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID taskExecutionId;
    private ImplementationReportResponseDto implementationReport;
    private ReportSynthesisResponseDto synthesis;
    private String synthesisStatus; // "AVAILABLE", "NONE", "FAILED"

    public TaskReportResponseDto() {
    }

    public TaskReportResponseDto(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID taskExecutionId,
            ImplementationReportResponseDto implementationReport,
            ReportSynthesisResponseDto synthesis,
            String synthesisStatus) {
        this.projectId = projectId;
        this.repositoryId = repositoryId;
        this.taskId = taskId;
        this.taskExecutionId = taskExecutionId;
        this.implementationReport = implementationReport;
        this.synthesis = synthesis;
        this.synthesisStatus = synthesisStatus;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(UUID repositoryId) {
        this.repositoryId = repositoryId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getTaskExecutionId() {
        return taskExecutionId;
    }

    public void setTaskExecutionId(UUID taskExecutionId) {
        this.taskExecutionId = taskExecutionId;
    }

    public ImplementationReportResponseDto getImplementationReport() {
        return implementationReport;
    }

    public void setImplementationReport(ImplementationReportResponseDto implementationReport) {
        this.implementationReport = implementationReport;
    }

    public ReportSynthesisResponseDto getSynthesis() {
        return synthesis;
    }

    public void setSynthesis(ReportSynthesisResponseDto synthesis) {
        this.synthesis = synthesis;
    }

    public String getSynthesisStatus() {
        return synthesisStatus;
    }

    public void setSynthesisStatus(String synthesisStatus) {
        this.synthesisStatus = synthesisStatus;
    }
}

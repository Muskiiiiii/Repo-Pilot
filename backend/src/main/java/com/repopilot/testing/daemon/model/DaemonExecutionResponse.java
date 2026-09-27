package com.repopilot.testing.daemon.model;

import java.util.UUID;

public class DaemonExecutionResponse {

    private String requestId;
    private DaemonStatus status;
    private String errorCode;
    private String errorMessage;
    private UUID jobId;
    private String executionId;
    private Long fencingVersion;
    private Long timestampMs;
    private String details;

    public DaemonExecutionResponse() {
    }

    public DaemonExecutionResponse(String requestId, DaemonStatus status, String errorCode, String errorMessage,
                                   UUID jobId, String executionId, Long fencingVersion, Long timestampMs, String details) {
        this.requestId = requestId;
        this.status = status;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.jobId = jobId;
        this.executionId = executionId;
        this.fencingVersion = fencingVersion;
        this.timestampMs = timestampMs;
        this.details = details;
    }

    public static DaemonExecutionResponse success(String requestId, UUID jobId, String executionId, Long fencingVersion, String details) {
        return new DaemonExecutionResponse(requestId, DaemonStatus.SUCCESS, null, null, jobId, executionId, fencingVersion, System.currentTimeMillis(), details);
    }

    public static DaemonExecutionResponse error(String requestId, DaemonStatus status, String errorCode, String errorMessage, UUID jobId, String executionId, Long fencingVersion) {
        return new DaemonExecutionResponse(requestId, status, errorCode, errorMessage, jobId, executionId, fencingVersion, System.currentTimeMillis(), null);
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public DaemonStatus getStatus() {
        return status;
    }

    public void setStatus(DaemonStatus status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public Long getFencingVersion() {
        return fencingVersion;
    }

    public void setFencingVersion(Long fencingVersion) {
        this.fencingVersion = fencingVersion;
    }

    public Long getTimestampMs() {
        return timestampMs;
    }

    public void setTimestampMs(Long timestampMs) {
        this.timestampMs = timestampMs;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}

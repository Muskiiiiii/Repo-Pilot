package com.repopilot.testing.daemon.model;

import java.util.UUID;

public class DaemonExecutionRequest {

    private String requestId;
    private UUID jobId;
    private UUID workerNodeId;
    private UUID leaseToken;
    private Long fencingVersion;
    private String executionId;
    private DaemonOperationType operationType;
    private String commandType;
    private String commandArgs;
    private Integer timeoutSeconds;
    private Long requestTimestampMs;

    public DaemonExecutionRequest() {
    }

    public DaemonExecutionRequest(String requestId, UUID jobId, UUID workerNodeId, UUID leaseToken,
                                  Long fencingVersion, String executionId, DaemonOperationType operationType,
                                  String commandType, String commandArgs, Integer timeoutSeconds, Long requestTimestampMs) {
        this.requestId = requestId;
        this.jobId = jobId;
        this.workerNodeId = workerNodeId;
        this.leaseToken = leaseToken;
        this.fencingVersion = fencingVersion;
        this.executionId = executionId;
        this.operationType = operationType;
        this.commandType = commandType;
        this.commandArgs = commandArgs;
        this.timeoutSeconds = timeoutSeconds;
        this.requestTimestampMs = requestTimestampMs;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public UUID getWorkerNodeId() {
        return workerNodeId;
    }

    public void setWorkerNodeId(UUID workerNodeId) {
        this.workerNodeId = workerNodeId;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(UUID leaseToken) {
        this.leaseToken = leaseToken;
    }

    public Long getFencingVersion() {
        return fencingVersion;
    }

    public void setFencingVersion(Long fencingVersion) {
        this.fencingVersion = fencingVersion;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public DaemonOperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(DaemonOperationType operationType) {
        this.operationType = operationType;
    }

    public String getCommandType() {
        return commandType;
    }

    public void setCommandType(String commandType) {
        this.commandType = commandType;
    }

    public String getCommandArgs() {
        return commandArgs;
    }

    public void setCommandArgs(String commandArgs) {
        this.commandArgs = commandArgs;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Long getRequestTimestampMs() {
        return requestTimestampMs;
    }

    public void setRequestTimestampMs(Long requestTimestampMs) {
        this.requestTimestampMs = requestTimestampMs;
    }
}

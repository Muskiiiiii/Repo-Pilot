package com.repopilot.change.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class CodeChangeSetResponseDto {

    private UUID taskId;
    private UUID planId;
    private String status; // GENERATED, INSUFFICIENT_CONTEXT, VALIDATION_FAILED
    private String summary;
    private List<CodeChangeResponseDto> changes;
    private Instant createdAt;

    public CodeChangeSetResponseDto() {
    }

    public CodeChangeSetResponseDto(UUID taskId, UUID planId, String status, String summary, List<CodeChangeResponseDto> changes, Instant createdAt) {
        this.taskId = taskId;
        this.planId = planId;
        this.status = status;
        this.summary = summary;
        this.changes = changes;
        this.createdAt = createdAt;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getPlanId() {
        return planId;
    }

    public void setPlanId(UUID planId) {
        this.planId = planId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<CodeChangeResponseDto> getChanges() {
        return changes;
    }

    public void setChanges(List<CodeChangeResponseDto> changes) {
        this.changes = changes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

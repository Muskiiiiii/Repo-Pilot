package com.repopilot.change.dto;

import com.repopilot.task.dto.EvidenceReferenceDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class CodeChangeResponseDto {

    private UUID id;
    private UUID taskId;
    private UUID planId;
    private UUID repositoryId;
    private String filePath;
    private String changeType; // CREATE, MODIFY, DELETE
    private String reason;
    private String oldContent;
    private String newContent;
    private String originalHash;
    private String proposedHash;
    private String diff;
    private List<String> affectedSymbols;
    private List<EvidenceReferenceDto> evidenceReferences;
    private String confidenceLevel;
    private Double confidenceScore;
    private Integer startLine;
    private Integer endLine;
    private String status; // GENERATED, VALIDATION_FAILED, REJECTED, APPROVED
    private String validationFailureReason;
    private Instant createdAt;
    private Instant updatedAt;

    public CodeChangeResponseDto() {
    }

    public CodeChangeResponseDto(UUID id, UUID taskId, UUID planId, UUID repositoryId, String filePath,
                                String changeType, String reason, String oldContent, String newContent,
                                String originalHash, String proposedHash, String diff,
                                List<String> affectedSymbols, List<EvidenceReferenceDto> evidenceReferences,
                                String confidenceLevel, Double confidenceScore, Integer startLine, Integer endLine,
                                String status, String validationFailureReason, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.taskId = taskId;
        this.planId = planId;
        this.repositoryId = repositoryId;
        this.filePath = filePath;
        this.changeType = changeType;
        this.reason = reason;
        this.oldContent = oldContent;
        this.newContent = newContent;
        this.originalHash = originalHash;
        this.proposedHash = proposedHash;
        this.diff = diff;
        this.affectedSymbols = affectedSymbols;
        this.evidenceReferences = evidenceReferences;
        this.confidenceLevel = confidenceLevel;
        this.confidenceScore = confidenceScore;
        this.startLine = startLine;
        this.endLine = endLine;
        this.status = status;
        this.validationFailureReason = validationFailureReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(UUID repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getOldContent() {
        return oldContent;
    }

    public void setOldContent(String oldContent) {
        this.oldContent = oldContent;
    }

    public String getNewContent() {
        return newContent;
    }

    public void setNewContent(String newContent) {
        this.newContent = newContent;
    }

    public String getOriginalHash() {
        return originalHash;
    }

    public void setOriginalHash(String originalHash) {
        this.originalHash = originalHash;
    }

    public String getProposedHash() {
        return proposedHash;
    }

    public void setProposedHash(String proposedHash) {
        this.proposedHash = proposedHash;
    }

    public String getDiff() {
        return diff;
    }

    public void setDiff(String diff) {
        this.diff = diff;
    }

    public List<String> getAffectedSymbols() {
        return affectedSymbols;
    }

    public void setAffectedSymbols(List<String> affectedSymbols) {
        this.affectedSymbols = affectedSymbols;
    }

    public List<EvidenceReferenceDto> getEvidenceReferences() {
        return evidenceReferences;
    }

    public void setEvidenceReferences(List<EvidenceReferenceDto> evidenceReferences) {
        this.evidenceReferences = evidenceReferences;
    }

    public String getConfidenceLevel() {
        return confidenceLevel;
    }

    public void setConfidenceLevel(String confidenceLevel) {
        this.confidenceLevel = confidenceLevel;
    }

    public Double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(Double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public void setStartLine(Integer startLine) {
        this.startLine = startLine;
    }

    public Integer getEndLine() {
        return endLine;
    }

    public void setEndLine(Integer endLine) {
        this.endLine = endLine;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getValidationFailureReason() {
        return validationFailureReason;
    }

    public void setValidationFailureReason(String validationFailureReason) {
        this.validationFailureReason = validationFailureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

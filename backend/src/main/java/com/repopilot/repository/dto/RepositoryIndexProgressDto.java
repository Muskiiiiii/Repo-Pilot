package com.repopilot.repository.dto;

import com.repopilot.repository.entity.RepositoryIndexStatus;

import java.util.UUID;

public class RepositoryIndexProgressDto {

    private UUID repositoryId;
    private RepositoryIndexStatus status;
    private int totalFiles;
    private int processedFiles;
    private int totalChunks;
    private int embeddedChunks;
    private int remainingEmbeddings;
    private double progressPercentage;
    private Double ratePerMinute;
    private Long estimatedRemainingSeconds;
    private String lastProgressAt;
    private String health;
    private boolean canStop;
    private boolean canResume;
    private String errorMessage;

    public RepositoryIndexProgressDto() {
    }

    public RepositoryIndexProgressDto(UUID repositoryId, RepositoryIndexStatus status, int totalFiles, int processedFiles, int totalChunks, int embeddedChunks, double progressPercentage, String errorMessage) {
        this(repositoryId, status, totalFiles, processedFiles, totalChunks, embeddedChunks, Math.max(0, totalChunks - embeddedChunks), progressPercentage, null, null, null, null, status == RepositoryIndexStatus.INDEXING, status == RepositoryIndexStatus.FAILED || status == RepositoryIndexStatus.CANCELLED || status == RepositoryIndexStatus.NOT_INDEXED, errorMessage);
    }

    public RepositoryIndexProgressDto(UUID repositoryId, RepositoryIndexStatus status, int totalFiles, int processedFiles, int totalChunks, int embeddedChunks, int remainingEmbeddings, double progressPercentage, Double ratePerMinute, Long estimatedRemainingSeconds, String lastProgressAt, String health, boolean canStop, boolean canResume, String errorMessage) {
        this.repositoryId = repositoryId;
        this.status = status;
        this.totalFiles = totalFiles;
        this.processedFiles = processedFiles;
        this.totalChunks = totalChunks;
        this.embeddedChunks = embeddedChunks;
        this.remainingEmbeddings = remainingEmbeddings;
        this.progressPercentage = progressPercentage;
        this.ratePerMinute = ratePerMinute;
        this.estimatedRemainingSeconds = estimatedRemainingSeconds;
        this.lastProgressAt = lastProgressAt;
        this.health = health;
        this.canStop = canStop;
        this.canResume = canResume;
        this.errorMessage = errorMessage;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(UUID repositoryId) {
        this.repositoryId = repositoryId;
    }

    public RepositoryIndexStatus getStatus() {
        return status;
    }

    public void setStatus(RepositoryIndexStatus status) {
        this.status = status;
    }

    public int getTotalFiles() {
        return totalFiles;
    }

    public void setTotalFiles(int totalFiles) {
        this.totalFiles = totalFiles;
    }

    public int getProcessedFiles() {
        return processedFiles;
    }

    public void setProcessedFiles(int processedFiles) {
        this.processedFiles = processedFiles;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public int getEmbeddedChunks() {
        return embeddedChunks;
    }

    public void setEmbeddedChunks(int embeddedChunks) {
        this.embeddedChunks = embeddedChunks;
    }

    public double getProgressPercentage() {
        return progressPercentage;
    }

    public void setProgressPercentage(double progressPercentage) {
        this.progressPercentage = progressPercentage;
    }

    public int getRemainingEmbeddings() {
        return remainingEmbeddings;
    }

    public void setRemainingEmbeddings(int remainingEmbeddings) {
        this.remainingEmbeddings = remainingEmbeddings;
    }

    public Double getRatePerMinute() {
        return ratePerMinute;
    }

    public void setRatePerMinute(Double ratePerMinute) {
        this.ratePerMinute = ratePerMinute;
    }

    public Long getEstimatedRemainingSeconds() {
        return estimatedRemainingSeconds;
    }

    public void setEstimatedRemainingSeconds(Long estimatedRemainingSeconds) {
        this.estimatedRemainingSeconds = estimatedRemainingSeconds;
    }

    public String getLastProgressAt() {
        return lastProgressAt;
    }

    public void setLastProgressAt(String lastProgressAt) {
        this.lastProgressAt = lastProgressAt;
    }

    public String getHealth() {
        return health;
    }

    public void setHealth(String health) {
        this.health = health;
    }

    public boolean isCanStop() {
        return canStop;
    }

    public void setCanStop(boolean canStop) {
        this.canStop = canStop;
    }

    public boolean isCanResume() {
        return canResume;
    }

    public void setCanResume(boolean canResume) {
        this.canResume = canResume;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}

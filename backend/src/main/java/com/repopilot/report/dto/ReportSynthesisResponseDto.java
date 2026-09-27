package com.repopilot.report.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ReportSynthesisResponseDto {

    private UUID id;
    private UUID implementationReportId;
    private UUID taskExecutionId;
    private UUID projectId;
    private String sourceContextHash;
    private String aiSynthesisInputHash;
    private String synthesizerVersion;
    private String promptVersion;
    private String modelIdentifier;
    private String validatorVersion;
    private String executiveSummary;
    private String implementationExplanation;
    private String testingExplanation;
    private String securityExplanation;
    private List<String> limitations = new ArrayList<>();
    private List<String> developerNotes = new ArrayList<>();
    private String renderedMarkdown;
    private Instant createdAt;

    public ReportSynthesisResponseDto() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getImplementationReportId() {
        return implementationReportId;
    }

    public void setImplementationReportId(UUID implementationReportId) {
        this.implementationReportId = implementationReportId;
    }

    public UUID getTaskExecutionId() {
        return taskExecutionId;
    }

    public void setTaskExecutionId(UUID taskExecutionId) {
        this.taskExecutionId = taskExecutionId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public String getSourceContextHash() {
        return sourceContextHash;
    }

    public void setSourceContextHash(String sourceContextHash) {
        this.sourceContextHash = sourceContextHash;
    }

    public String getAiSynthesisInputHash() {
        return aiSynthesisInputHash;
    }

    public void setAiSynthesisInputHash(String aiSynthesisInputHash) {
        this.aiSynthesisInputHash = aiSynthesisInputHash;
    }

    public String getSynthesizerVersion() {
        return synthesizerVersion;
    }

    public void setSynthesizerVersion(String synthesizerVersion) {
        this.synthesizerVersion = synthesizerVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getModelIdentifier() {
        return modelIdentifier;
    }

    public void setModelIdentifier(String modelIdentifier) {
        this.modelIdentifier = modelIdentifier;
    }

    public String getValidatorVersion() {
        return validatorVersion;
    }

    public void setValidatorVersion(String validatorVersion) {
        this.validatorVersion = validatorVersion;
    }

    public String getExecutiveSummary() {
        return executiveSummary;
    }

    public void setExecutiveSummary(String executiveSummary) {
        this.executiveSummary = executiveSummary;
    }

    public String getImplementationExplanation() {
        return implementationExplanation;
    }

    public void setImplementationExplanation(String implementationExplanation) {
        this.implementationExplanation = implementationExplanation;
    }

    public String getTestingExplanation() {
        return testingExplanation;
    }

    public void setTestingExplanation(String testingExplanation) {
        this.testingExplanation = testingExplanation;
    }

    public String getSecurityExplanation() {
        return securityExplanation;
    }

    public void setSecurityExplanation(String securityExplanation) {
        this.securityExplanation = securityExplanation;
    }

    public List<String> getLimitations() {
        return limitations;
    }

    public void setLimitations(List<String> limitations) {
        this.limitations = (limitations != null) ? limitations : new ArrayList<>();
    }

    public List<String> getDeveloperNotes() {
        return developerNotes;
    }

    public void setDeveloperNotes(List<String> developerNotes) {
        this.developerNotes = (developerNotes != null) ? developerNotes : new ArrayList<>();
    }

    public String getRenderedMarkdown() {
        return renderedMarkdown;
    }

    public void setRenderedMarkdown(String renderedMarkdown) {
        this.renderedMarkdown = renderedMarkdown;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

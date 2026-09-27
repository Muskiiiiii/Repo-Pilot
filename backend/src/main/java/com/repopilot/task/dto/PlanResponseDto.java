package com.repopilot.task.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class PlanResponseDto {

    private UUID id;
    private UUID taskId;
    private Integer version;
    private String summary;
    private String problemAnalysis;
    private List<String> relevantFiles;
    private List<String> relevantSymbols;
    private String currentBehavior;
    private List<ProposedChangeDto> proposedChanges;
    private List<String> stepByStepPlan;
    private String dataApiChanges;
    private List<String> testingStrategy;
    private List<String> risks;
    private List<String> assumptions;
    private List<String> openQuestions;
    private String confidenceLevel;
    private Double confidenceScore;
    private Boolean isAmbiguous;
    private String ambiguityReason;
    private List<EvidenceReferenceDto> evidenceReferences;
    private String status;
    private Instant createdAt;

    public PlanResponseDto() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getProblemAnalysis() { return problemAnalysis; }
    public void setProblemAnalysis(String problemAnalysis) { this.problemAnalysis = problemAnalysis; }

    public List<String> getRelevantFiles() { return relevantFiles; }
    public void setRelevantFiles(List<String> relevantFiles) { this.relevantFiles = relevantFiles; }

    public List<String> getRelevantSymbols() { return relevantSymbols; }
    public void setRelevantSymbols(List<String> relevantSymbols) { this.relevantSymbols = relevantSymbols; }

    public String getCurrentBehavior() { return currentBehavior; }
    public void setCurrentBehavior(String currentBehavior) { this.currentBehavior = currentBehavior; }

    public List<ProposedChangeDto> getProposedChanges() { return proposedChanges; }
    public void setProposedChanges(List<ProposedChangeDto> proposedChanges) { this.proposedChanges = proposedChanges; }

    public List<String> getStepByStepPlan() { return stepByStepPlan; }
    public void setStepByStepPlan(List<String> stepByStepPlan) { this.stepByStepPlan = stepByStepPlan; }

    public String getDataApiChanges() { return dataApiChanges; }
    public void setDataApiChanges(String dataApiChanges) { this.dataApiChanges = dataApiChanges; }

    public List<String> getTestingStrategy() { return testingStrategy; }
    public void setTestingStrategy(List<String> testingStrategy) { this.testingStrategy = testingStrategy; }

    public List<String> getRisks() { return risks; }
    public void setRisks(List<String> risks) { this.risks = risks; }

    public List<String> getAssumptions() { return assumptions; }
    public void setAssumptions(List<String> assumptions) { this.assumptions = assumptions; }

    public List<String> getOpenQuestions() { return openQuestions; }
    public void setOpenQuestions(List<String> openQuestions) { this.openQuestions = openQuestions; }

    public String getConfidenceLevel() { return confidenceLevel; }
    public void setConfidenceLevel(String confidenceLevel) { this.confidenceLevel = confidenceLevel; }

    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

    public Boolean getIsAmbiguous() { return isAmbiguous; }
    public void setIsAmbiguous(Boolean isAmbiguous) { this.isAmbiguous = isAmbiguous; }

    public String getAmbiguityReason() { return ambiguityReason; }
    public void setAmbiguityReason(String ambiguityReason) { this.ambiguityReason = ambiguityReason; }

    public List<EvidenceReferenceDto> getEvidenceReferences() { return evidenceReferences; }
    public void setEvidenceReferences(List<EvidenceReferenceDto> evidenceReferences) { this.evidenceReferences = evidenceReferences; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

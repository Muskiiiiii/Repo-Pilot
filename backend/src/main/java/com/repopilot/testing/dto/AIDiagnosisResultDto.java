package com.repopilot.testing.dto;

import java.util.ArrayList;
import java.util.List;

public class AIDiagnosisResultDto {

    private String rootCause;
    private String explanation;
    private String confidence;
    private List<String> affectedFiles = new ArrayList<>();
    private String failureMechanism;
    private String repairRecommendation;

    public AIDiagnosisResultDto() {
    }

    public AIDiagnosisResultDto(String rootCause, String explanation, String confidence,
                               List<String> affectedFiles, String failureMechanism, String repairRecommendation) {
        this.rootCause = rootCause;
        this.explanation = explanation;
        this.confidence = confidence;
        if (affectedFiles != null) {
            this.affectedFiles = affectedFiles;
        }
        this.failureMechanism = failureMechanism;
        this.repairRecommendation = repairRecommendation;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public List<String> getAffectedFiles() {
        return affectedFiles;
    }

    public void setAffectedFiles(List<String> affectedFiles) {
        this.affectedFiles = affectedFiles;
    }

    public String getFailureMechanism() {
        return failureMechanism;
    }

    public void setFailureMechanism(String failureMechanism) {
        this.failureMechanism = failureMechanism;
    }

    public String getRepairRecommendation() {
        return repairRecommendation;
    }

    public void setRepairRecommendation(String repairRecommendation) {
        this.repairRecommendation = repairRecommendation;
    }
}

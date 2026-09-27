package com.repopilot.testing.dto;

import com.repopilot.testing.entity.ConfidenceLevel;
import com.repopilot.testing.entity.FailureType;
import com.repopilot.testing.entity.Repairability;

import java.time.Instant;
import java.util.Map;

public class FailureClassification {

    private final FailureType failureCategory;
    private final Repairability repairability;
    private final ConfidenceLevel confidence;
    private final String reason;
    private final String repairBlockReason;
    private final String classifierVersion;
    private final Instant classifiedAt;
    private final boolean isRelatedToCodeChanges;
    private final Map<String, Object> evidence;

    public FailureClassification(FailureType failureCategory, Repairability repairability, ConfidenceLevel confidence,
                                 String reason, String repairBlockReason, String classifierVersion,
                                 Instant classifiedAt, boolean isRelatedToCodeChanges, Map<String, Object> evidence) {
        this.failureCategory = failureCategory != null ? failureCategory : FailureType.UNKNOWN;
        this.repairability = repairability != null ? repairability : Repairability.AMBIGUOUS;
        this.confidence = confidence != null ? confidence : ConfidenceLevel.LOW;
        this.reason = reason;
        this.repairBlockReason = repairBlockReason;
        this.classifierVersion = classifierVersion != null ? classifierVersion : "10.3-v1";
        this.classifiedAt = classifiedAt != null ? classifiedAt : Instant.now();
        this.isRelatedToCodeChanges = isRelatedToCodeChanges;
        this.evidence = evidence != null ? evidence : Map.of();
    }

    public FailureType getFailureCategory() {
        return failureCategory;
    }

    public Repairability getRepairability() {
        return repairability;
    }

    public ConfidenceLevel getConfidence() {
        return confidence;
    }

    public String getReason() {
        return reason;
    }

    public String getRepairBlockReason() {
        return repairBlockReason;
    }

    public String getClassifierVersion() {
        return classifierVersion;
    }

    public Instant getClassifiedAt() {
        return classifiedAt;
    }

    public boolean isRelatedToCodeChanges() {
        return isRelatedToCodeChanges;
    }

    public Map<String, Object> getEvidence() {
        return evidence;
    }
}

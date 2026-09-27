package com.repopilot.plan.entity;

import com.repopilot.task.entity.Task;
import com.repopilot.user.entity.User;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "plans")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String objective;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String requirements;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String steps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String risks;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "affected_files", columnDefinition = "jsonb")
    private String affectedFiles;

    @Column(name = "confidence_score", precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    @Column(name = "confidence_level", length = 20)
    private String confidenceLevel = "MEDIUM";

    @Column(name = "is_ambiguous")
    private Boolean isAmbiguous = false;

    @Column(name = "ambiguity_reason", columnDefinition = "TEXT")
    private String ambiguityReason;

    @Column(name = "problem_analysis", columnDefinition = "TEXT")
    private String problemAnalysis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "relevant_symbols", columnDefinition = "jsonb")
    private String relevantSymbols;

    @Column(name = "current_behavior", columnDefinition = "TEXT")
    private String currentBehavior;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_changes", columnDefinition = "jsonb")
    private String proposedChanges;

    @Column(name = "data_api_changes", columnDefinition = "TEXT")
    private String dataApiChanges;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "testing_strategy", columnDefinition = "jsonb")
    private String testingStrategy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String assumptions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "open_questions", columnDefinition = "jsonb")
    private String openQuestions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_references", columnDefinition = "jsonb")
    private String evidenceReferences;

    @Column(nullable = false)
    private String status = "DRAFT";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "approved_at")
    private Instant approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    public Plan() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getObjective() {
        return objective;
    }

    public void setObjective(String objective) {
        this.objective = objective;
    }

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String requirements) {
        this.requirements = requirements;
    }

    public String getSteps() {
        return steps;
    }

    public void setSteps(String steps) {
        this.steps = steps;
    }

    public String getRisks() {
        return risks;
    }

    public void setRisks(String risks) {
        this.risks = risks;
    }

    public String getAffectedFiles() {
        return affectedFiles;
    }

    public void setAffectedFiles(String affectedFiles) {
        this.affectedFiles = affectedFiles;
    }

    public BigDecimal getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(BigDecimal confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getConfidenceLevel() {
        return confidenceLevel;
    }

    public void setConfidenceLevel(String confidenceLevel) {
        this.confidenceLevel = confidenceLevel;
    }

    public Boolean getIsAmbiguous() {
        return isAmbiguous;
    }

    public void setIsAmbiguous(Boolean isAmbiguous) {
        this.isAmbiguous = isAmbiguous;
    }

    public String getAmbiguityReason() {
        return ambiguityReason;
    }

    public void setAmbiguityReason(String ambiguityReason) {
        this.ambiguityReason = ambiguityReason;
    }

    public String getProblemAnalysis() {
        return problemAnalysis;
    }

    public void setProblemAnalysis(String problemAnalysis) {
        this.problemAnalysis = problemAnalysis;
    }

    public String getRelevantSymbols() {
        return relevantSymbols;
    }

    public void setRelevantSymbols(String relevantSymbols) {
        this.relevantSymbols = relevantSymbols;
    }

    public String getCurrentBehavior() {
        return currentBehavior;
    }

    public void setCurrentBehavior(String currentBehavior) {
        this.currentBehavior = currentBehavior;
    }

    public String getProposedChanges() {
        return proposedChanges;
    }

    public void setProposedChanges(String proposedChanges) {
        this.proposedChanges = proposedChanges;
    }

    public String getDataApiChanges() {
        return dataApiChanges;
    }

    public void setDataApiChanges(String dataApiChanges) {
        this.dataApiChanges = dataApiChanges;
    }

    public String getTestingStrategy() {
        return testingStrategy;
    }

    public void setTestingStrategy(String testingStrategy) {
        this.testingStrategy = testingStrategy;
    }

    public String getAssumptions() {
        return assumptions;
    }

    public void setAssumptions(String assumptions) {
        this.assumptions = assumptions;
    }

    public String getOpenQuestions() {
        return openQuestions;
    }

    public void setOpenQuestions(String openQuestions) {
        this.openQuestions = openQuestions;
    }

    public String getEvidenceReferences() {
        return evidenceReferences;
    }

    public void setEvidenceReferences(String evidenceReferences) {
        this.evidenceReferences = evidenceReferences;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public User getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(User approvedBy) {
        this.approvedBy = approvedBy;
    }
}

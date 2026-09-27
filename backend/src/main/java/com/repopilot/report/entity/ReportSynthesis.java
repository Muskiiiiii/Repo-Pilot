package com.repopilot.report.entity;

import com.repopilot.project.entity.Project;
import com.repopilot.task.entity.TaskExecution;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "report_syntheses", uniqueConstraints = {
    @UniqueConstraint(name = "uk_report_syntheses_input_hash", columnNames = {"implementation_report_id", "ai_synthesis_input_hash"})
})
public class ReportSynthesis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "implementation_report_id", nullable = false)
    private ImplementationReport implementationReport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_execution_id", nullable = false)
    private TaskExecution taskExecution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "source_context_hash", nullable = false, length = 64, updatable = false)
    private String sourceContextHash;

    @Column(name = "ai_synthesis_input_hash", nullable = false, length = 64, updatable = false)
    private String aiSynthesisInputHash;

    @Column(name = "synthesizer_version", nullable = false, length = 50, updatable = false)
    private String synthesizerVersion = "v1.0";

    @Column(name = "prompt_version", nullable = false, length = 50, updatable = false)
    private String promptVersion = "v1.0";

    @Column(name = "model_identifier", nullable = false, length = 100, updatable = false)
    private String modelIdentifier = "repopilot-report-synthesizer-v1";

    @Column(name = "validator_version", nullable = false, length = 50, updatable = false)
    private String validatorVersion = "v1.0";

    @Column(name = "executive_summary", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String executiveSummary;

    @Column(name = "implementation_explanation", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String implementationExplanation;

    @Column(name = "testing_explanation", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String testingExplanation;

    @Column(name = "security_explanation", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String securityExplanation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "limitations_json", columnDefinition = "jsonb", updatable = false)
    private String limitationsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "developer_notes_json", columnDefinition = "jsonb", updatable = false)
    private String developerNotesJson;

    @Column(name = "rendered_markdown", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String renderedMarkdown;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ReportSynthesis() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public ImplementationReport getImplementationReport() {
        return implementationReport;
    }

    public void setImplementationReport(ImplementationReport implementationReport) {
        this.implementationReport = implementationReport;
    }

    public TaskExecution getTaskExecution() {
        return taskExecution;
    }

    public void setTaskExecution(TaskExecution taskExecution) {
        this.taskExecution = taskExecution;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
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

    public String getLimitationsJson() {
        return limitationsJson;
    }

    public void setLimitationsJson(String limitationsJson) {
        this.limitationsJson = limitationsJson;
    }

    public String getDeveloperNotesJson() {
        return developerNotesJson;
    }

    public void setDeveloperNotesJson(String developerNotesJson) {
        this.developerNotesJson = developerNotesJson;
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

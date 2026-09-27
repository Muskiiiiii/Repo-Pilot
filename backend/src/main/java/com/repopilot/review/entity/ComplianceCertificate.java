package com.repopilot.review.entity;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.project.entity.Project;
import com.repopilot.repository.entity.Repository;
import com.repopilot.review.dto.SecurityPolicyComplianceCertificateDto;
import com.repopilot.task.entity.Task;
import com.repopilot.user.entity.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "compliance_certificates", uniqueConstraints = {
    @UniqueConstraint(name = "uk_compliance_cert_project_review_idempotency", columnNames = {"project_id", "code_review_id", "idempotency_key"}),
    @UniqueConstraint(name = "uk_compliance_cert_idempotency_global", columnNames = {"idempotency_key"}),
    @UniqueConstraint(name = "uk_compliance_cert_hash", columnNames = {"certificate_hash"})
})
public class ComplianceCertificate {

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "code_review_id", nullable = false)
    private CodeReview codeReview;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "code_change_id", nullable = false)
    private CodeChange codeChange;

    @Enumerated(EnumType.STRING)
    @Column(name = "compliance_status", nullable = false, length = 30)
    private PolicyComplianceStatus complianceStatus;

    @Column(name = "policy_version", nullable = false, length = 50)
    private String policyVersion = "v1.0.0";

    @Column(name = "review_context_hash", nullable = false, length = 64)
    private String reviewContextHash;

    @Column(name = "finding_evidence_hash", nullable = false, length = 64)
    private String findingEvidenceHash;

    @Column(name = "critical_unmitigated_count", nullable = false)
    private Integer criticalUnmitigatedCount = 0;

    @Column(name = "high_unmitigated_count", nullable = false)
    private Integer highUnmitigatedCount = 0;

    @Column(name = "medium_unmitigated_count", nullable = false)
    private Integer mediumUnmitigatedCount = 0;

    @Column(name = "low_unmitigated_count", nullable = false)
    private Integer lowUnmitigatedCount = 0;

    @Column(name = "mitigated_findings_count", nullable = false)
    private Integer mitigatedFindingsCount = 0;

    @Column(name = "admin_override_reason", columnDefinition = "TEXT")
    private String adminOverrideReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_override_by_user_id")
    private User adminOverrideByUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issued_by_user_id", nullable = false)
    private User issuedByUser;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "certificate_hash", nullable = false, length = 64)
    private String certificateHash;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ComplianceCertificate() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public Repository getRepository() {
        return repository;
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public CodeReview getCodeReview() {
        return codeReview;
    }

    public void setCodeReview(CodeReview codeReview) {
        this.codeReview = codeReview;
    }

    public CodeChange getCodeChange() {
        return codeChange;
    }

    public void setCodeChange(CodeChange codeChange) {
        this.codeChange = codeChange;
    }

    public PolicyComplianceStatus getComplianceStatus() {
        return complianceStatus;
    }

    public void setComplianceStatus(PolicyComplianceStatus complianceStatus) {
        this.complianceStatus = complianceStatus;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public String getReviewContextHash() {
        return reviewContextHash;
    }

    public void setReviewContextHash(String reviewContextHash) {
        this.reviewContextHash = reviewContextHash;
    }

    public String getFindingEvidenceHash() {
        return findingEvidenceHash;
    }

    public void setFindingEvidenceHash(String findingEvidenceHash) {
        this.findingEvidenceHash = findingEvidenceHash;
    }

    public Integer getCriticalUnmitigatedCount() {
        return criticalUnmitigatedCount;
    }

    public void setCriticalUnmitigatedCount(Integer criticalUnmitigatedCount) {
        this.criticalUnmitigatedCount = criticalUnmitigatedCount;
    }

    public Integer getHighUnmitigatedCount() {
        return highUnmitigatedCount;
    }

    public void setHighUnmitigatedCount(Integer highUnmitigatedCount) {
        this.highUnmitigatedCount = highUnmitigatedCount;
    }

    public Integer getMediumUnmitigatedCount() {
        return mediumUnmitigatedCount;
    }

    public void setMediumUnmitigatedCount(Integer mediumUnmitigatedCount) {
        this.mediumUnmitigatedCount = mediumUnmitigatedCount;
    }

    public Integer getLowUnmitigatedCount() {
        return lowUnmitigatedCount;
    }

    public void setLowUnmitigatedCount(Integer lowUnmitigatedCount) {
        this.lowUnmitigatedCount = lowUnmitigatedCount;
    }

    public Integer getMitigatedFindingsCount() {
        return mitigatedFindingsCount;
    }

    public void setMitigatedFindingsCount(Integer mitigatedFindingsCount) {
        this.mitigatedFindingsCount = mitigatedFindingsCount;
    }

    public String getAdminOverrideReason() {
        return adminOverrideReason;
    }

    public void setAdminOverrideReason(String adminOverrideReason) {
        this.adminOverrideReason = adminOverrideReason;
    }

    public User getAdminOverrideByUser() {
        return adminOverrideByUser;
    }

    public void setAdminOverrideByUser(User adminOverrideByUser) {
        this.adminOverrideByUser = adminOverrideByUser;
    }

    public User getIssuedByUser() {
        return issuedByUser;
    }

    public void setIssuedByUser(User issuedByUser) {
        this.issuedByUser = issuedByUser;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getCertificateHash() {
        return certificateHash;
    }

    public void setCertificateHash(String certificateHash) {
        this.certificateHash = certificateHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public SecurityPolicyComplianceCertificateDto toDto() {
        return new SecurityPolicyComplianceCertificateDto(
                id,
                codeReview != null ? codeReview.getId() : null,
                project != null ? project.getId() : null,
                repository != null ? repository.getId() : null,
                task != null ? task.getId() : null,
                codeChange != null ? codeChange.getId() : null,
                complianceStatus,
                policyVersion,
                reviewContextHash,
                findingEvidenceHash,
                criticalUnmitigatedCount,
                highUnmitigatedCount,
                mediumUnmitigatedCount,
                lowUnmitigatedCount,
                mitigatedFindingsCount,
                adminOverrideReason,
                adminOverrideByUser != null ? adminOverrideByUser.getId() : null,
                issuedByUser != null ? issuedByUser.getId() : null,
                idempotencyKey,
                certificateHash,
                createdAt
        );
    }
}

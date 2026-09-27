package com.repopilot.llm.entity;

import com.repopilot.organization.entity.Organization;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_overage_liabilities")
public class OrganizationOverageLiability {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private LlmProviderAttempt attempt;

    @Column(name = "financial_liability_micro_usd", nullable = false)
    private Long financialLiabilityMicroUsd;

    @Column(name = "reason", nullable = false, length = 128)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public OrganizationOverageLiability() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public void setOrganization(Organization organization) {
        this.organization = organization;
    }

    public LlmProviderAttempt getAttempt() {
        return attempt;
    }

    public void setAttempt(LlmProviderAttempt attempt) {
        this.attempt = attempt;
    }

    public Long getFinancialLiabilityMicroUsd() {
        return financialLiabilityMicroUsd;
    }

    public void setFinancialLiabilityMicroUsd(Long financialLiabilityMicroUsd) {
        this.financialLiabilityMicroUsd = financialLiabilityMicroUsd;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

package com.repopilot.organization.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_policies")
public class OrganizationPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, unique = true)
    private Organization organization;

    @Column(name = "monthly_token_budget", nullable = false)
    private Long monthlyTokenBudget = 1000000L;

    @Column(name = "monthly_spend_limit_usd", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlySpendLimitUsd = new BigDecimal("100.00");

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_llm_models_json", columnDefinition = "jsonb", nullable = false)
    private String allowedLlmModelsJson = "[\"claude-3-5-sonnet\", \"gpt-4o\"]";

    @Column(name = "enforce_security_review", nullable = false)
    private boolean enforceSecurityReview = true;

    @Column(name = "max_concurrent_executions", nullable = false)
    private int maxConcurrentExecutions = 5;

    @Column(name = "audit_retention_days", nullable = false)
    private int auditRetentionDays = 90;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public OrganizationPolicy() {
    }

    public OrganizationPolicy(Organization organization) {
        this.organization = organization;
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
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

    public Long getMonthlyTokenBudget() {
        return monthlyTokenBudget;
    }

    public void setMonthlyTokenBudget(Long monthlyTokenBudget) {
        this.monthlyTokenBudget = monthlyTokenBudget;
    }

    public BigDecimal getMonthlySpendLimitUsd() {
        return monthlySpendLimitUsd;
    }

    public void setMonthlySpendLimitUsd(BigDecimal monthlySpendLimitUsd) {
        this.monthlySpendLimitUsd = monthlySpendLimitUsd;
    }

    public String getAllowedLlmModelsJson() {
        return allowedLlmModelsJson;
    }

    public void setAllowedLlmModelsJson(String allowedLlmModelsJson) {
        this.allowedLlmModelsJson = allowedLlmModelsJson;
    }

    public boolean isEnforceSecurityReview() {
        return enforceSecurityReview;
    }

    public void setEnforceSecurityReview(boolean enforceSecurityReview) {
        this.enforceSecurityReview = enforceSecurityReview;
    }

    public int getMaxConcurrentExecutions() {
        return maxConcurrentExecutions;
    }

    public void setMaxConcurrentExecutions(int maxConcurrentExecutions) {
        this.maxConcurrentExecutions = maxConcurrentExecutions;
    }

    public int getAuditRetentionDays() {
        return auditRetentionDays;
    }

    public void setAuditRetentionDays(int auditRetentionDays) {
        this.auditRetentionDays = auditRetentionDays;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

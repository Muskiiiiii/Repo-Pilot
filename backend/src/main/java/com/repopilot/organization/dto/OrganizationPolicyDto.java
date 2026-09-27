package com.repopilot.organization.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class OrganizationPolicyDto {

    private UUID id;
    private UUID organizationId;
    private Long monthlyTokenBudget;
    private BigDecimal monthlySpendLimitUsd;
    private List<String> allowedLlmModels;
    private boolean enforceSecurityReview;
    private int maxConcurrentExecutions;
    private int auditRetentionDays;
    private Long version;
    private Instant updatedAt;

    public OrganizationPolicyDto() {
    }

    public OrganizationPolicyDto(UUID id, UUID organizationId, Long monthlyTokenBudget, BigDecimal monthlySpendLimitUsd, List<String> allowedLlmModels, boolean enforceSecurityReview, int maxConcurrentExecutions, int auditRetentionDays, Long version, Instant updatedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.monthlyTokenBudget = monthlyTokenBudget;
        this.monthlySpendLimitUsd = monthlySpendLimitUsd;
        this.allowedLlmModels = allowedLlmModels;
        this.enforceSecurityReview = enforceSecurityReview;
        this.maxConcurrentExecutions = maxConcurrentExecutions;
        this.auditRetentionDays = auditRetentionDays;
        this.version = version;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
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

    public List<String> getAllowedLlmModels() {
        return allowedLlmModels;
    }

    public void setAllowedLlmModels(List<String> allowedLlmModels) {
        this.allowedLlmModels = allowedLlmModels;
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

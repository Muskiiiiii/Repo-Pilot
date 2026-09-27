package com.repopilot.organization.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public class UpdateOrganizationPolicyRequest {

    @NotNull(message = "Monthly token budget is required")
    @Min(value = 0, message = "Monthly token budget must be non-negative")
    private Long monthlyTokenBudget;

    @NotNull(message = "Monthly spend limit USD is required")
    @Min(value = 0, message = "Monthly spend limit USD must be non-negative")
    private BigDecimal monthlySpendLimitUsd;

    @NotEmpty(message = "Allowed LLM models list cannot be empty")
    private List<String> allowedLlmModels;

    private boolean enforceSecurityReview = true;

    @Min(value = 1, message = "Max concurrent executions must be at least 1")
    @Max(value = 100, message = "Max concurrent executions cannot exceed 100")
    private int maxConcurrentExecutions = 5;

    @Min(value = 1, message = "Audit retention days must be at least 1")
    @Max(value = 3650, message = "Audit retention days cannot exceed 3650")
    private int auditRetentionDays = 90;

    @NotNull(message = "Version is required for concurrency protection")
    private Long version;

    public UpdateOrganizationPolicyRequest() {
    }

    public UpdateOrganizationPolicyRequest(Long monthlyTokenBudget, BigDecimal monthlySpendLimitUsd, List<String> allowedLlmModels, boolean enforceSecurityReview, int maxConcurrentExecutions, int auditRetentionDays, Long version) {
        this.monthlyTokenBudget = monthlyTokenBudget;
        this.monthlySpendLimitUsd = monthlySpendLimitUsd;
        this.allowedLlmModels = allowedLlmModels;
        this.enforceSecurityReview = enforceSecurityReview;
        this.maxConcurrentExecutions = maxConcurrentExecutions;
        this.auditRetentionDays = auditRetentionDays;
        this.version = version;
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
}

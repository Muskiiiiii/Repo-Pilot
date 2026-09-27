package com.repopilot.llm.dto;

import com.repopilot.llm.entity.LlmBillingStatus;
import com.repopilot.llm.entity.LlmExecutionStatus;
import com.repopilot.llm.entity.LlmProviderAttempt;

import java.time.Instant;
import java.util.UUID;

public class ProviderAttemptInspectionResponse {

    private UUID attemptId;
    private UUID logicalRequestId;
    private Integer attemptNumber;
    private String providerId;
    private String modelId;
    private LlmExecutionStatus executionStatus;
    private LlmBillingStatus billingStatus;
    private String fallbackReason;
    private UUID fallbackDecisionId;
    private String inputHash;
    private String outputHash;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long estimatedCostMicroUsd;
    private Long calculatedCostMicroUsd;
    private String providerRequestId;
    private String traceId;
    private String spanId;
    private Instant startTime;
    private Instant endTime;
    private Long latencyMs;

    public ProviderAttemptInspectionResponse() {
    }

    public static ProviderAttemptInspectionResponse fromEntity(LlmProviderAttempt entity) {
        if (entity == null) return null;
        ProviderAttemptInspectionResponse dto = new ProviderAttemptInspectionResponse();
        dto.setAttemptId(entity.getId());
        dto.setLogicalRequestId(entity.getLogicalRequest() != null ? entity.getLogicalRequest().getId() : null);
        dto.setAttemptNumber(entity.getAttemptNumber());
        dto.setProviderId(entity.getProviderId());
        dto.setModelId(entity.getModelId());
        dto.setExecutionStatus(entity.getExecutionStatus());
        dto.setBillingStatus(entity.getBillingStatus());
        dto.setFallbackReason(entity.getFallbackReason());
        dto.setFallbackDecisionId(entity.getFallbackDecision() != null ? entity.getFallbackDecision().getId() : null);
        dto.setInputHash(entity.getInputHash());
        dto.setOutputHash(entity.getOutputHash());
        dto.setPromptTokens(entity.getPromptTokens());
        dto.setCompletionTokens(entity.getCompletionTokens());
        dto.setTotalTokens(entity.getTotalTokens());
        dto.setEstimatedCostMicroUsd(entity.getEstimatedCostMicroUsd());
        dto.setCalculatedCostMicroUsd(entity.getCalculatedCostMicroUsd());
        dto.setProviderRequestId(entity.getProviderRequestId());
        dto.setTraceId(entity.getTraceId());
        dto.setSpanId(entity.getSpanId());
        dto.setStartTime(entity.getStartTime());
        dto.setEndTime(entity.getEndTime());
        dto.setLatencyMs(entity.getLatencyMs());
        return dto;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(UUID attemptId) {
        this.attemptId = attemptId;
    }

    public UUID getLogicalRequestId() {
        return logicalRequestId;
    }

    public void setLogicalRequestId(UUID logicalRequestId) {
        this.logicalRequestId = logicalRequestId;
    }

    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(Integer attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public LlmExecutionStatus getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(LlmExecutionStatus executionStatus) {
        this.executionStatus = executionStatus;
    }

    public LlmBillingStatus getBillingStatus() {
        return billingStatus;
    }

    public void setBillingStatus(LlmBillingStatus billingStatus) {
        this.billingStatus = billingStatus;
    }

    public String getFallbackReason() {
        return fallbackReason;
    }

    public void setFallbackReason(String fallbackReason) {
        this.fallbackReason = fallbackReason;
    }

    public UUID getFallbackDecisionId() {
        return fallbackDecisionId;
    }

    public void setFallbackDecisionId(UUID fallbackDecisionId) {
        this.fallbackDecisionId = fallbackDecisionId;
    }

    public String getInputHash() {
        return inputHash;
    }

    public void setInputHash(String inputHash) {
        this.inputHash = inputHash;
    }

    public String getOutputHash() {
        return outputHash;
    }

    public void setOutputHash(String outputHash) {
        this.outputHash = outputHash;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Long getEstimatedCostMicroUsd() {
        return estimatedCostMicroUsd;
    }

    public void setEstimatedCostMicroUsd(Long estimatedCostMicroUsd) {
        this.estimatedCostMicroUsd = estimatedCostMicroUsd;
    }

    public Long getCalculatedCostMicroUsd() {
        return calculatedCostMicroUsd;
    }

    public void setCalculatedCostMicroUsd(Long calculatedCostMicroUsd) {
        this.calculatedCostMicroUsd = calculatedCostMicroUsd;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public void setProviderRequestId(String providerRequestId) {
        this.providerRequestId = providerRequestId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public void setSpanId(String spanId) {
        this.spanId = spanId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }
}

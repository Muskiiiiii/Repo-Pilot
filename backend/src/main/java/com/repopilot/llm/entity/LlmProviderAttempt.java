package com.repopilot.llm.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_provider_attempts", uniqueConstraints = {
    @UniqueConstraint(name = "uk_llm_attempt_seq", columnNames = {"logical_request_id", "attempt_number"})
})
public class LlmProviderAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "logical_request_id", nullable = false)
    private LlmLogicalRequest logicalRequest;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "provider_id", nullable = false, length = 32)
    private String providerId;

    @Column(name = "model_id", nullable = false, length = 64)
    private String modelId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pricing_version_id", nullable = false)
    private LlmPricingVersion pricingVersion;

    @Column(name = "provider_idempotency_key", nullable = false, length = 128)
    private String providerIdempotencyKey;

    @Column(name = "provider_request_id", length = 128)
    private String providerRequestId;

    @Column(name = "worker_lease_token", length = 128)
    private String workerLeaseToken;

    @Column(name = "fencing_version", nullable = false)
    private Long fencingVersion = 1L;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Column(name = "output_hash", length = 64)
    private String outputHash;

    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens = 0;

    @Column(name = "total_tokens", nullable = false)
    private Integer totalTokens = 0;

    @Column(name = "estimated_cost_micro_usd", nullable = false)
    private Long estimatedCostMicroUsd = 0L;

    @Column(name = "calculated_cost_micro_usd", nullable = false)
    private Long calculatedCostMicroUsd = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false, length = 32)
    private LlmExecutionStatus executionStatus = LlmExecutionStatus.ATTEMPT_CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_status", nullable = false, length = 32)
    private LlmBillingStatus billingStatus = LlmBillingStatus.UNBILLED;

    @Column(name = "fallback_reason", length = 64)
    private String fallbackReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fallback_decision_id")
    private LlmFallbackDecision fallbackDecision;

    @Column(name = "trace_id", length = 32)
    private String traceId;

    @Column(name = "span_id", length = 16)
    private String spanId;

    @Column(name = "start_time", nullable = false, updatable = false)
    private Instant startTime = Instant.now();

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "latency_ms")
    private Long latencyMs;

    public LlmProviderAttempt() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public LlmLogicalRequest getLogicalRequest() {
        return logicalRequest;
    }

    public void setLogicalRequest(LlmLogicalRequest logicalRequest) {
        this.logicalRequest = logicalRequest;
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

    public LlmPricingVersion getPricingVersion() {
        return pricingVersion;
    }

    public void setPricingVersion(LlmPricingVersion pricingVersion) {
        this.pricingVersion = pricingVersion;
    }

    public String getProviderIdempotencyKey() {
        return providerIdempotencyKey;
    }

    public void setProviderIdempotencyKey(String providerIdempotencyKey) {
        this.providerIdempotencyKey = providerIdempotencyKey;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public void setProviderRequestId(String providerRequestId) {
        this.providerRequestId = providerRequestId;
    }

    public String getWorkerLeaseToken() {
        return workerLeaseToken;
    }

    public void setWorkerLeaseToken(String workerLeaseToken) {
        this.workerLeaseToken = workerLeaseToken;
    }

    public Long getFencingVersion() {
        return fencingVersion;
    }

    public void setFencingVersion(Long fencingVersion) {
        this.fencingVersion = fencingVersion;
    }

    public Instant getClaimExpiresAt() {
        return claimExpiresAt;
    }

    public void setClaimExpiresAt(Instant claimExpiresAt) {
        this.claimExpiresAt = claimExpiresAt;
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

    public LlmFallbackDecision getFallbackDecision() {
        return fallbackDecision;
    }

    public void setFallbackDecision(LlmFallbackDecision fallbackDecision) {
        this.fallbackDecision = fallbackDecision;
    }
}

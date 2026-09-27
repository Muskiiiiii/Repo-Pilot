package com.repopilot.llm.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_pricing_versions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_llm_pricing_ver", columnNames = {"provider_id", "model_id", "pricing_version"})
})
public class LlmPricingVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "provider_id", nullable = false, length = 32)
    private String providerId;

    @Column(name = "model_id", nullable = false, length = 64)
    private String modelId;

    @Column(name = "pricing_version", nullable = false)
    private Integer pricingVersion;

    @Column(name = "input_rate_micro_usd_per_1k", nullable = false)
    private Long inputRateMicroUsdPer1k;

    @Column(name = "output_rate_micro_usd_per_1k", nullable = false)
    private Long outputRateMicroUsdPer1k;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom = Instant.now();

    @Column(name = "effective_until")
    private Instant effectiveUntil;

    public LlmPricingVersion() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public Integer getPricingVersion() {
        return pricingVersion;
    }

    public void setPricingVersion(Integer pricingVersion) {
        this.pricingVersion = pricingVersion;
    }

    public Long getInputRateMicroUsdPer1k() {
        return inputRateMicroUsdPer1k;
    }

    public void setInputRateMicroUsdPer1k(Long inputRateMicroUsdPer1k) {
        this.inputRateMicroUsdPer1k = inputRateMicroUsdPer1k;
    }

    public Long getOutputRateMicroUsdPer1k() {
        return outputRateMicroUsdPer1k;
    }

    public void setOutputRateMicroUsdPer1k(Long outputRateMicroUsdPer1k) {
        this.outputRateMicroUsdPer1k = outputRateMicroUsdPer1k;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(Instant effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Instant getEffectiveUntil() {
        return effectiveUntil;
    }

    public void setEffectiveUntil(Instant effectiveUntil) {
        this.effectiveUntil = effectiveUntil;
    }
}

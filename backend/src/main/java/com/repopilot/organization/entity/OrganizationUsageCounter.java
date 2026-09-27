package com.repopilot.organization.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_usage_counters")
public class OrganizationUsageCounter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, unique = true)
    private Organization organization;

    @Column(name = "tokens_consumed", nullable = false)
    private Long tokensConsumed = 0L;

    @Column(name = "total_spend_cents", nullable = false)
    private Long totalSpendCents = 0L;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart = Instant.now();

    @Column(name = "reserved_tokens", nullable = false)
    private Integer reservedTokens = 0;

    @Column(name = "reserved_spend_micro_usd", nullable = false)
    private Long reservedSpendMicroUsd = 0L;

    @Column(name = "unresolved_exposure_tokens", nullable = false)
    private Integer unresolvedExposureTokens = 0;

    @Column(name = "unresolved_exposure_spend_micro_usd", nullable = false)
    private Long unresolvedExposureSpendMicroUsd = 0L;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public OrganizationUsageCounter() {
    }

    public OrganizationUsageCounter(Organization organization) {
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

    public Long getTokensConsumed() {
        return tokensConsumed;
    }

    public void setTokensConsumed(Long tokensConsumed) {
        this.tokensConsumed = tokensConsumed;
    }

    public Long getTotalSpendCents() {
        return totalSpendCents;
    }

    public void setTotalSpendCents(Long totalSpendCents) {
        this.totalSpendCents = totalSpendCents;
    }

    public Instant getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(Instant periodStart) {
        this.periodStart = periodStart;
    }

    public Integer getReservedTokens() {
        return reservedTokens;
    }

    public void setReservedTokens(Integer reservedTokens) {
        this.reservedTokens = reservedTokens;
    }

    public Long getReservedSpendMicroUsd() {
        return reservedSpendMicroUsd;
    }

    public void setReservedSpendMicroUsd(Long reservedSpendMicroUsd) {
        this.reservedSpendMicroUsd = reservedSpendMicroUsd;
    }

    public Integer getUnresolvedExposureTokens() {
        return unresolvedExposureTokens;
    }

    public void setUnresolvedExposureTokens(Integer unresolvedExposureTokens) {
        this.unresolvedExposureTokens = unresolvedExposureTokens;
    }

    public Long getUnresolvedExposureSpendMicroUsd() {
        return unresolvedExposureSpendMicroUsd;
    }

    public void setUnresolvedExposureSpendMicroUsd(Long unresolvedExposureSpendMicroUsd) {
        this.unresolvedExposureSpendMicroUsd = unresolvedExposureSpendMicroUsd;
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

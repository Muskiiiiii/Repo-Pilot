package com.repopilot.llm.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log_outbox", indexes = {
    @Index(name = "idx_audit_log_outbox_org_seq", columnList = "organization_id, sequence_number")
})
public class AuditLogOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "canonical_audit_event_id", nullable = false, unique = true, length = 128)
    private String canonicalAuditEventId;

    @Column(name = "occurrence_id", nullable = false)
    private UUID occurrenceId;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 128)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_state", nullable = false, length = 32)
    private AuditOutboxDeliveryState deliveryState = AuditOutboxDeliveryState.PENDING;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @org.hibernate.annotations.Generated
    @Column(name = "sequence_number", columnDefinition = "BIGSERIAL", insertable = false, updatable = false)
    private Long sequenceNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private com.repopilot.organization.entity.Organization organization;

    public AuditLogOutbox() {
    }

    public com.repopilot.organization.entity.Organization getOrganization() {
        return organization;
    }

    public void setOrganization(com.repopilot.organization.entity.Organization organization) {
        this.organization = organization;
    }

    public Long getSequenceNumber() {
        return sequenceNumber != null ? sequenceNumber : 0L;
    }

    public void setSequenceNumber(Long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCanonicalAuditEventId() {
        return canonicalAuditEventId;
    }

    public void setCanonicalAuditEventId(String canonicalAuditEventId) {
        this.canonicalAuditEventId = canonicalAuditEventId;
    }

    public UUID getOccurrenceId() {
        return occurrenceId;
    }

    public void setOccurrenceId(UUID occurrenceId) {
        this.occurrenceId = occurrenceId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public AuditOutboxDeliveryState getDeliveryState() {
        return deliveryState;
    }

    public void setDeliveryState(AuditOutboxDeliveryState deliveryState) {
        this.deliveryState = deliveryState;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
}

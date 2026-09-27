package com.repopilot.llm.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

@JsonPropertyOrder({
        "sequenceNumber",
        "canonicalAuditEventId",
        "organizationId",
        "occurrenceId",
        "eventType",
        "aggregateType",
        "aggregateId",
        "createdAt",
        "payload"
})
public class CanonicalAuditExportRecord {

    private Long sequenceNumber;
    private String canonicalAuditEventId;
    private String organizationId;
    private UUID occurrenceId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private String createdAt;
    private JsonNode payload;

    public CanonicalAuditExportRecord() {
    }

    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(Long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public String getCanonicalAuditEventId() {
        return canonicalAuditEventId;
    }

    public void setCanonicalAuditEventId(String canonicalAuditEventId) {
        this.canonicalAuditEventId = canonicalAuditEventId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public UUID getOccurrenceId() {
        return occurrenceId;
    }

    public void setOccurrenceId(UUID occurrenceId) {
        this.occurrenceId = occurrenceId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
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

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }
}

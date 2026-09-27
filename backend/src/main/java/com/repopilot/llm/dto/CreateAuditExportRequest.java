package com.repopilot.llm.dto;

import java.time.Instant;

public class CreateAuditExportRequest {

    private String exportScope;
    private Instant filterFromTimestamp;
    private Instant filterToTimestamp;
    private String idempotencyKey;

    public CreateAuditExportRequest() {
    }

    public CreateAuditExportRequest(String exportScope, Instant filterFromTimestamp, Instant filterToTimestamp, String idempotencyKey) {
        this.exportScope = exportScope;
        this.filterFromTimestamp = filterFromTimestamp;
        this.filterToTimestamp = filterToTimestamp;
        this.idempotencyKey = idempotencyKey;
    }

    public String getExportScope() {
        return exportScope;
    }

    public void setExportScope(String exportScope) {
        this.exportScope = exportScope;
    }

    public Instant getFilterFromTimestamp() {
        return filterFromTimestamp;
    }

    public void setFilterFromTimestamp(Instant filterFromTimestamp) {
        this.filterFromTimestamp = filterFromTimestamp;
    }

    public Instant getFilterToTimestamp() {
        return filterToTimestamp;
    }

    public void setFilterToTimestamp(Instant filterToTimestamp) {
        this.filterToTimestamp = filterToTimestamp;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}

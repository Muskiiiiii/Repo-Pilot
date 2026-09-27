package com.repopilot.testing.dto;

public class RepairLoopRequestDto {

    private String idempotencyKey;
    private String clientReferenceId;

    public RepairLoopRequestDto() {}

    public RepairLoopRequestDto(String idempotencyKey, String clientReferenceId) {
        this.idempotencyKey = idempotencyKey;
        this.clientReferenceId = clientReferenceId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getClientReferenceId() {
        return clientReferenceId;
    }

    public void setClientReferenceId(String clientReferenceId) {
        this.clientReferenceId = clientReferenceId;
    }
}

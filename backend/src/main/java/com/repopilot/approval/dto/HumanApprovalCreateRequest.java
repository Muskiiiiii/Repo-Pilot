package com.repopilot.approval.dto;

public class HumanApprovalCreateRequest {

    private String repositoryBaseSha;
    private String idempotencyKey;
    private boolean confirmAction = true;

    public HumanApprovalCreateRequest() {
    }

    public HumanApprovalCreateRequest(String repositoryBaseSha, String idempotencyKey, boolean confirmAction) {
        this.repositoryBaseSha = repositoryBaseSha;
        this.idempotencyKey = idempotencyKey;
        this.confirmAction = confirmAction;
    }

    public String getRepositoryBaseSha() {
        return repositoryBaseSha;
    }

    public void setRepositoryBaseSha(String repositoryBaseSha) {
        this.repositoryBaseSha = repositoryBaseSha;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public boolean isConfirmAction() {
        return confirmAction;
    }

    public void setConfirmAction(boolean confirmAction) {
        this.confirmAction = confirmAction;
    }
}

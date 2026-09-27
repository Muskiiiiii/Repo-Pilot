package com.repopilot.approval.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MutationIntegrityResult(
        UUID operationId,
        UUID approvalId,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        UUID codeChangeId,
        MutationIntegrityStatus status,
        String failureReason,
        String baselineTreeHash,
        String resultingTreeHash,
        int changedPathCount,
        List<String> approvedChangedPaths,
        Instant verifiedAt
) {
    public boolean isVerified() {
        return status == MutationIntegrityStatus.VERIFIED;
    }

    public static MutationIntegrityResult verified(
            UUID operationId,
            UUID approvalId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            String baselineTreeHash,
            String resultingTreeHash,
            List<String> approvedChangedPaths
    ) {
        return new MutationIntegrityResult(
                operationId,
                approvalId,
                projectId,
                repositoryId,
                taskId,
                codeChangeId,
                MutationIntegrityStatus.VERIFIED,
                null,
                baselineTreeHash,
                resultingTreeHash,
                approvedChangedPaths != null ? approvedChangedPaths.size() : 0,
                approvedChangedPaths != null ? approvedChangedPaths : List.of(),
                Instant.now()
        );
    }

    public static MutationIntegrityResult failure(
            UUID operationId,
            UUID approvalId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            MutationIntegrityStatus status,
            String failureReason
    ) {
        return new MutationIntegrityResult(
                operationId,
                approvalId,
                projectId,
                repositoryId,
                taskId,
                codeChangeId,
                status,
                failureReason,
                null,
                null,
                0,
                List.of(),
                Instant.now()
        );
    }
}

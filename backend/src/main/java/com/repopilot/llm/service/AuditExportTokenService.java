package com.repopilot.llm.service;

import com.repopilot.llm.dto.AuditExportTokenClaims;
import com.repopilot.llm.dto.AuditExportTokenResponse;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface AuditExportTokenService {
    AuditExportTokenResponse createDownloadToken(UUID orgId, UUID jobId, User caller);

    AuditExportTokenClaims verifyAndConsumeToken(String tokenString, UUID requestedOrgId, UUID requestedJobId, User caller);
}

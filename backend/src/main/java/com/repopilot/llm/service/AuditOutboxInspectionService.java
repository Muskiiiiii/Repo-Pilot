package com.repopilot.llm.service;

import com.repopilot.llm.dto.AuditOutboxInspectionResponse;
import com.repopilot.llm.dto.PagedPolicyResponse;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface AuditOutboxInspectionService {

    PagedPolicyResponse<AuditOutboxInspectionResponse> getAuditOutboxEvents(UUID orgId, int page, int size, User caller);

    AuditOutboxInspectionResponse getAuditOutboxEvent(UUID orgId, UUID eventId, User caller);
}

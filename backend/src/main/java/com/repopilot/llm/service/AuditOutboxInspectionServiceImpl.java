package com.repopilot.llm.service;

import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.AuditOutboxInspectionResponse;
import com.repopilot.llm.dto.PagedPolicyResponse;
import com.repopilot.llm.entity.AuditLogOutbox;
import com.repopilot.llm.repository.AuditLogOutboxRepository;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.service.OrganizationAuthorizationService;
import com.repopilot.user.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class AuditOutboxInspectionServiceImpl implements AuditOutboxInspectionService {

    private final AuditLogOutboxRepository auditLogOutboxRepository;
    private final OrganizationAuthorizationService organizationAuthorizationService;

    @Autowired
    public AuditOutboxInspectionServiceImpl(AuditLogOutboxRepository auditLogOutboxRepository,
                                             OrganizationAuthorizationService organizationAuthorizationService) {
        this.auditLogOutboxRepository = auditLogOutboxRepository;
        this.organizationAuthorizationService = organizationAuthorizationService;
    }

    @Override
    public PagedPolicyResponse<AuditOutboxInspectionResponse> getAuditOutboxEvents(UUID orgId, int page, int size, User caller) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_MEMBER);

        if (size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Page size cannot exceed 100");
        }
        if (size < 1 || page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid page or size parameter");
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogOutbox> outboxPage = auditLogOutboxRepository.findByOrganizationId(orgId, pageable);

        List<AuditOutboxInspectionResponse> content = outboxPage.getContent().stream()
                .map(AuditOutboxInspectionResponse::fromEntity)
                .collect(Collectors.toList());

        return new PagedPolicyResponse<>(content, outboxPage.getNumber(), outboxPage.getSize(), outboxPage.getTotalElements(), outboxPage.getTotalPages());
    }

    @Override
    public AuditOutboxInspectionResponse getAuditOutboxEvent(UUID orgId, UUID eventId, User caller) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_MEMBER);

        AuditLogOutbox event = auditLogOutboxRepository.findByIdAndOrganizationId(eventId, orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Audit event not found for organization"));

        return AuditOutboxInspectionResponse.fromEntity(event);
    }
}

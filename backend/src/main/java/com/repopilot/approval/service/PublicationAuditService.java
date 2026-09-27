package com.repopilot.approval.service;

import com.repopilot.approval.dto.PublicationAuditEventResponseDto;
import com.repopilot.approval.entity.AuditEventType;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.entity.PublicationAuditEvent;
import com.repopilot.approval.entity.PullRequestPublication;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.project.entity.Project;
import com.repopilot.repository.entity.Repository;
import com.repopilot.task.entity.Task;
import com.repopilot.user.entity.User;

import java.util.List;
import java.util.UUID;

public interface PublicationAuditService {

    PublicationAuditEvent recordEvent(
            Project project,
            Repository repository,
            Task task,
            CodeChange codeChange,
            HumanApproval approval,
            PullRequestPublication publication,
            User actor,
            AuditEventType eventType,
            String previousState,
            String resultingState,
            String outcomeCategory,
            String reasonCode,
            String summary,
            String metadataJson
    );

    List<PublicationAuditEventResponseDto> getTaskAuditEvents(
            UUID projectId, UUID repositoryId, UUID taskId, User requester
    );

    List<PublicationAuditEventResponseDto> getCodeChangeAuditEvents(
            UUID projectId, UUID codeChangeId, User requester
    );
}

package com.repopilot.approval.repository;

import com.repopilot.approval.entity.PublicationAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PublicationAuditEventRepository extends JpaRepository<PublicationAuditEvent, UUID> {

    List<PublicationAuditEvent> findByProjectIdAndRepositoryIdAndTaskIdOrderByEventSequenceAsc(
            UUID projectId, UUID repositoryId, UUID taskId
    );

    List<PublicationAuditEvent> findByProjectIdAndCodeChangeIdOrderByEventSequenceAsc(
            UUID projectId, UUID codeChangeId
    );

    List<PublicationAuditEvent> findByProjectIdAndApprovalIdOrderByEventSequenceAsc(
            UUID projectId, UUID approvalId
    );
}

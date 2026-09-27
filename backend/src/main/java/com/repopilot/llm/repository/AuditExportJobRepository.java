package com.repopilot.llm.repository;

import com.repopilot.llm.entity.AuditExportJob;
import com.repopilot.llm.entity.AuditExportJobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditExportJobRepository extends JpaRepository<AuditExportJob, UUID> {

    @Query("SELECT j FROM AuditExportJob j WHERE j.organization.id = :orgId AND j.id = :jobId")
    Optional<AuditExportJob> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("jobId") UUID jobId);

    @Query("SELECT j FROM AuditExportJob j WHERE j.organization.id = :orgId AND j.idempotencyKey = :idempotencyKey")
    Optional<AuditExportJob> findByOrganizationIdAndIdempotencyKey(@Param("orgId") UUID orgId, @Param("idempotencyKey") String idempotencyKey);

    @Query("SELECT j FROM AuditExportJob j WHERE j.organization.id = :orgId ORDER BY j.createdAt DESC")
    Page<AuditExportJob> findByOrganizationIdOrderByCreatedAtDesc(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT COUNT(j) FROM AuditExportJob j WHERE j.organization.id = :orgId AND j.status IN :statuses")
    long countByOrganizationIdAndStatusIn(@Param("orgId") UUID orgId, @Param("statuses") List<AuditExportJobStatus> statuses);

    @Modifying
    @Query("UPDATE AuditExportJob j SET j.status = :toStatus, j.updatedAt = :now WHERE j.id = :jobId AND j.leaseToken = :leaseToken AND j.fencingVersion = :expectedFencing AND j.status = :fromStatus")
    int updateStatusFenced(@Param("jobId") UUID jobId,
                           @Param("leaseToken") UUID leaseToken,
                           @Param("expectedFencing") Long expectedFencing,
                           @Param("fromStatus") AuditExportJobStatus fromStatus,
                           @Param("toStatus") AuditExportJobStatus toStatus,
                           @Param("now") Instant now);

    @Query("SELECT j FROM AuditExportJob j WHERE j.status = com.repopilot.llm.entity.AuditExportJobStatus.PENDING OR ((j.status = com.repopilot.llm.entity.AuditExportJobStatus.CLAIMED OR j.status = com.repopilot.llm.entity.AuditExportJobStatus.PROCESSING) AND j.leaseExpiresAt IS NOT NULL AND j.leaseExpiresAt < :now) ORDER BY j.createdAt ASC")
    List<AuditExportJob> findClaimableJobs(@Param("now") Instant now, Pageable pageable);
}

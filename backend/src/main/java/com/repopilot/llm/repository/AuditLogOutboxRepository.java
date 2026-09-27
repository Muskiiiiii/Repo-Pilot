package com.repopilot.llm.repository;

import com.repopilot.llm.entity.AuditLogOutbox;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditLogOutboxRepository extends JpaRepository<AuditLogOutbox, UUID> {
    Optional<AuditLogOutbox> findByCanonicalAuditEventId(String canonicalAuditEventId);

    @Query("SELECT o FROM AuditLogOutbox o WHERE o.organization.id = :orgId ORDER BY o.createdAt DESC, o.id DESC")
    Page<AuditLogOutbox> findByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT o FROM AuditLogOutbox o WHERE o.id = :eventId AND o.organization.id = :orgId")
    Optional<AuditLogOutbox> findByIdAndOrganizationId(@Param("eventId") UUID eventId, @Param("orgId") UUID orgId);

    @Query("SELECT o.id FROM AuditLogOutbox o WHERE o.organization.id = :orgId ORDER BY o.createdAt DESC, o.id DESC")
    Page<UUID> findLatestOutboxIdByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT MAX(o.sequenceNumber) FROM AuditLogOutbox o WHERE o.organization.id = :orgId")
    Long findMaxSequenceNumberByOrganizationId(@Param("orgId") UUID orgId);

    @Query("SELECT MAX(o.sequenceNumber) FROM AuditLogOutbox o")
    Long findMaxSequenceNumber();

    @Query("SELECT o FROM AuditLogOutbox o WHERE o.organization.id = :orgId " +
           "AND o.sequenceNumber > :afterSeq AND o.sequenceNumber <= :maxSeq " +
           "ORDER BY o.sequenceNumber ASC")
    java.util.List<AuditLogOutbox> findChunkForExport(
            @Param("orgId") UUID orgId,
            @Param("afterSeq") Long afterSeq,
            @Param("maxSeq") Long maxSeq,
            Pageable pageable);

    @Query("SELECT o FROM AuditLogOutbox o WHERE o.organization.id = :orgId " +
           "AND o.sequenceNumber > :afterSeq AND o.sequenceNumber <= :maxSeq " +
           "AND o.createdAt >= :fromTs AND o.createdAt <= :toTs " +
           "ORDER BY o.sequenceNumber ASC")
    java.util.List<AuditLogOutbox> findChunkForExportFiltered(
            @Param("orgId") UUID orgId,
            @Param("afterSeq") Long afterSeq,
            @Param("maxSeq") Long maxSeq,
            @Param("fromTs") java.time.Instant fromTs,
            @Param("toTs") java.time.Instant toTs,
            Pageable pageable);
}

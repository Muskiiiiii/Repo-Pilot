package com.repopilot.llm.repository;

import com.repopilot.llm.entity.LlmLogicalRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmLogicalRequestRepository extends JpaRepository<LlmLogicalRequest, UUID> {
    Optional<LlmLogicalRequest> findBySourceAuthorityTypeAndSourceAuthorityIdAndIdempotencyKey(
            String sourceAuthorityType, String sourceAuthorityId, String idempotencyKey);

    Optional<LlmLogicalRequest> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query(value = "SELECT * FROM llm_logical_requests WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<LlmLogicalRequest> findByIdForUpdate(@Param("id") UUID id);
}
